package com.aishorts.engine.claude;

import com.anthropic.backends.AnthropicBackend;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.SseException;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Iterator;
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
            boolean stopped = false;
            for (Iterator<RawMessageStreamEvent> events = response.stream().iterator(); events.hasNext(); ) {
                stopped |= accumulator.accumulate(events.next()).isMessageStop();
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
