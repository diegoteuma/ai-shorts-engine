package com.aishorts.engine.claude;

import com.anthropic.backends.AnthropicBackend;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonMissing;
import com.anthropic.core.JsonNull;
import com.anthropic.core.JsonObject;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.SseException;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.InputJsonDelta;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawContentBlockDelta;
import com.anthropic.models.messages.RawContentBlockDeltaEvent;
import com.anthropic.models.messages.RawContentBlockStartEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * La Messages API de Claude con streaming, vía el SDK oficial (anthropic-java):
 * el SDK arma el request, parsea el SSE y reconstruye el mensaje
 * (MessageAccumulator); acá solo se fija el transporte (Http11OkHttpClient)
 * y se traducen los errores a ClaudeApiException / ClaudeHttpException con
 * los mismos mensajes de siempre.
 *
 * Por qué streaming: una llamada con búsqueda web tarda minutos, y sin
 * streaming la conexión pasa todo ese tiempo sin tráfico; en una red con
 * inspección TLS se observó que la cortaban (Connection reset). Con
 * streaming llegan eventos y pings mientras el modelo trabaja.
 *
 * Sin reintentos (maxRetries 0), como el cliente anterior: una llamada con
 * búsqueda web es larga y paga.
 */
public final class ClaudeStreamingClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ClaudeStreamingClient.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final AnthropicClient client;
    private final Duration idleTimeout;

    /**
     * @param idleTimeout tiempo máximo sin recibir NINGÚN byte (ni un ping),
     *                    también para recibir los headers; no limita la
     *                    duración total mientras sigan llegando eventos.
     */
    public ClaudeStreamingClient(ClaudeConfig config, Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
        // El mismo Backend para el cliente y para el transporte: es el que agrega la versión de la API y la clave.
        AnthropicBackend backend = AnthropicBackend.builder()
                .apiKey(config.apiKey())
                .baseUrl(config.baseUrl())
                .build();
        this.client = AnthropicOkHttpClient.builder()
                .backend(backend)
                .maxRetries(0)
                .addInterceptor(sdkTransport -> new Http11OkHttpClient(idleTimeout, backend, sdkTransport))
                .build();
    }

    /** Manda el request con streaming y devuelve el mensaje completo; nunca uno a medio armar. */
    public Message stream(MessageCreateParams params) throws ClaudeApiException {
        StreamResponse<RawMessageStreamEvent> response;
        try {
            response = client.messages().createStreaming(params);
        } catch (AnthropicServiceException e) {
            throw new ClaudeHttpException(e.statusCode(), toJson(e.body()));
        } catch (RuntimeException e) {
            if (causedBy(e, SocketTimeoutException.class)) {
                throw new ClaudeApiException("La API de Claude no respondió dentro de " + idleTimeout.toSeconds() + " s", e);
            }
            throw networkOr(e, "Error de red llamando a la API de Claude", "Error llamando a la API de Claude");
        }

        try (response) {
            MessageAccumulator accumulator = MessageAccumulator.create();
            ToolInputRepair repair = new ToolInputRepair();
            boolean stopped = false;
            for (Iterator<RawMessageStreamEvent> events = response.stream().iterator(); events.hasNext(); ) {
                RawMessageStreamEvent event = events.next();
                logEvent(event);
                for (RawMessageStreamEvent repaired : repair.apply(event)) {
                    stopped |= accumulator.accumulate(repaired).isMessageStop();
                }
            }
            if (!stopped) {
                throw new ClaudeApiException("El stream de Claude terminó antes de message_stop (respuesta incompleta).");
            }
            return accumulator.message();
        } catch (SseException e) {
            throw new ClaudeApiException("La API de Claude envió un error en el stream: " + streamError(e.body()), e);
        } catch (AnthropicServiceException e) {
            throw new ClaudeHttpException(e.statusCode(), toJson(e.body()));
        } catch (ClaudeApiException e) {
            throw e;
        } catch (RuntimeException e) {
            if (causedBy(e, SocketTimeoutException.class)) {
                throw new ClaudeApiException("La API de Claude no envió eventos durante " + idleTimeout.toSeconds() + " s", e);
            }
            throw networkOr(e, "Error de red leyendo el stream de la API de Claude", "Error leyendo el stream de la API de Claude");
        }
    }

    /**
     * Un bloque (o cualquier modelo del SDK) como JSON, con TODOS sus campos,
     * incluidos los que el SDK no modela y los null explícitos: así se
     * reenvía intacto tras un pause_turn (encrypted_content, encrypted_index,
     * ids, ...). Directo del modelo y no vía Map: al serializar un Map, el
     * mapper del SDK descarta los valores null (por ejemplo "page_age": null).
     */
    public static JsonValue toJsonValue(Object sdkModel) {
        return ObjectMappers.jsonMapper().convertValue(sdkModel, JsonValue.class);
    }

    /** El mismo JSON como Map, solo para leerlo (texto, URLs); para reenviar, {@link #toJsonValue}. */
    public static Map<String, Object> toMap(Object sdkModel) {
        return ObjectMappers.jsonMapper().convertValue(sdkModel, MAP_TYPE);
    }

    @Override
    public void close() {
        client.close();
    }

    /**
     * MessageAccumulator (anthropic-java 2.71.0, visitContentBlockStop) exige
     * al menos un input_json_delta en cada bloque tool_use/server_tool_use, y
     * al cerrar el bloque REEMPLAZA el input del content_block_start por lo
     * que juntaron los deltas. Si el bloque llega con el input completo en el
     * start y sin deltas, falla con "Missing input JSON for index N". Esto
     * inyecta, justo antes de ese content_block_stop, un delta sintético con
     * el input del start (o "{}" si no traía): el resultado es el mismo
     * bloque que en la respuesta sin streaming. Si hubo deltas no toca nada.
     */
    static final class ToolInputRepair {

        /** Input del start de cada bloque de herramienta abierto, por índice; se borra al recibir su primer delta. */
        private final Map<Long, JsonValue> awaitingDelta = new HashMap<>();

        List<RawMessageStreamEvent> apply(RawMessageStreamEvent event) {
            if (event.isContentBlockStart()) {
                RawContentBlockStartEvent start = event.asContentBlockStart();
                RawContentBlockStartEvent.ContentBlock block = start.contentBlock();
                if (block.isToolUse()) {
                    awaitingDelta.put(start.index(), block.asToolUse()._input());
                } else if (block.isServerToolUse()) {
                    awaitingDelta.put(start.index(), block.asServerToolUse()._input());
                }
            } else if (event.isContentBlockDelta()) {
                RawContentBlockDeltaEvent delta = event.asContentBlockDelta();
                if (delta.delta().isInputJson()) {
                    awaitingDelta.remove(delta.index());
                }
            } else if (event.isContentBlockStop()) {
                long index = event.asContentBlockStop().index();
                if (awaitingDelta.containsKey(index)) {
                    String partialJson = inputAsJson(awaitingDelta.remove(index));
                    log.debug("Bloque de herramienta sin input_json_delta en el índice {}: se inyecta el input del start.", index);
                    RawMessageStreamEvent synthetic = RawMessageStreamEvent.ofContentBlockDelta(RawContentBlockDeltaEvent.builder()
                            .index(index)
                            .delta(InputJsonDelta.builder().partialJson(partialJson).build())
                            .build());
                    return List.of(synthetic, event);
                }
            }
            return List.of(event);
        }

        private static String inputAsJson(JsonValue input) {
            if (input == null || input instanceof JsonMissing || input instanceof JsonNull) {
                return "{}";
            }
            try {
                return ObjectMappers.jsonMapper().writeValueAsString(input);
            } catch (JsonProcessingException e) {
                return "{}";
            }
        }
    }

    /** DEBUG, por evento: tipo, índice y tipo de bloque o de delta. Nunca el contenido. */
    private static void logEvent(RawMessageStreamEvent event) {
        if (!log.isDebugEnabled()) {
            return;
        }
        if (event.isContentBlockStart()) {
            RawContentBlockStartEvent start = event.asContentBlockStart();
            RawContentBlockStartEvent.ContentBlock block = start.contentBlock();
            Map<String, Object> fields = toMap(block);
            Object blockType = fields.get("type");
            if (block.isToolUse() || block.isServerToolUse()) {
                // name es el nombre de la herramienta (web_search, code_execution...), no contenido
                JsonValue input = block.isToolUse() ? block.asToolUse()._input() : block.asServerToolUse()._input();
                log.debug("stream: content_block_start index={} block={} name={} inputInStart={}",
                        start.index(), blockType, fields.get("name"), describeInput(input));
            } else {
                log.debug("stream: content_block_start index={} block={}", start.index(), blockType);
            }
        } else if (event.isContentBlockDelta()) {
            RawContentBlockDeltaEvent delta = event.asContentBlockDelta();
            log.debug("stream: content_block_delta index={} delta={}", delta.index(), deltaType(delta.delta()));
        } else if (event.isContentBlockStop()) {
            log.debug("stream: content_block_stop index={}", event.asContentBlockStop().index());
        } else if (event.isMessageStart()) {
            log.debug("stream: message_start");
        } else if (event.isMessageDelta()) {
            log.debug("stream: message_delta stop_reason={}", event.asMessageDelta().delta().stopReason().map(Object::toString).orElse(null));
        } else if (event.isMessageStop()) {
            log.debug("stream: message_stop");
        } else {
            log.debug("stream: evento desconocido");
        }
    }

    /** "ausente", "null", "{}" o "objeto con N claves": suficiente para el diagnóstico, sin mostrar el input. */
    private static String describeInput(JsonValue input) {
        if (input == null || input instanceof JsonMissing) {
            return "ausente";
        }
        if (input instanceof JsonNull) {
            return "null";
        }
        if (input instanceof JsonObject object) {
            return object.values().isEmpty() ? "{}" : "objeto con " + object.values().size() + " claves";
        }
        return "no-objeto";
    }

    private static String deltaType(RawContentBlockDelta delta) {
        if (delta.isText()) return "text_delta";
        if (delta.isInputJson()) return "input_json_delta";
        if (delta.isCitations()) return "citations_delta";
        if (delta.isThinking()) return "thinking_delta";
        if (delta.isSignature()) return "signature_delta";
        return "desconocido";
    }

    /** "tipo: mensaje" del evento error del stream, o el JSON crudo si no tiene esa forma. */
    private static String streamError(JsonValue body) {
        Map<String, Object> event;
        try {
            event = toMap(body);
        } catch (IllegalArgumentException notAnObject) {
            return toJson(body);
        }
        if (event.get("error") instanceof Map<?, ?> error) {
            return error.get("type") + ": " + error.get("message");
        }
        return toJson(body);
    }

    private static String toJson(JsonValue value) {
        try {
            return ObjectMappers.jsonMapper().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    private static ClaudeApiException networkOr(RuntimeException e, String networkMessage, String otherMessage) {
        return new ClaudeApiException(causedBy(e, IOException.class) ? networkMessage : otherMessage, e);
    }

    private static boolean causedBy(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
