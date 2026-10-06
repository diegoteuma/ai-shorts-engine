package com.aishorts.engine.claude;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reconstruye, a partir de los eventos SSE de la Messages API con
 * "stream": true, la misma respuesta que devolvería la llamada sin
 * streaming: {content, stop_reason, usage, container}.
 *
 * Los bloques se arman tal como llegan (content_block_start trae el bloque
 * base; los deltas completan texto, citas, input JSON, thinking y firma), y
 * todo lo demás del bloque (encrypted_content, encrypted_index, ids, ...) se
 * conserva intacto, porque tras un pause_turn hay que reenviarlo sin
 * cambios. Un tipo de delta desconocido o un stream sin message_stop es un
 * error: un bloque a medio armar nunca se devuelve como si estuviera completo.
 */
public final class ClaudeStreamAccumulator {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final TreeMap<Integer, Map<String, Object>> blocks = new TreeMap<>();
    private final Map<Integer, StringBuilder> partialJson = new HashMap<>();
    private final Map<String, Object> usage = new LinkedHashMap<>();
    private String messageId;
    private Object stopReason;
    private Object container;
    private boolean started;
    private boolean stopped;

    public ClaudeStreamAccumulator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Procesa un evento SSE (el nombre del evento y su línea data, ya unidas). */
    @SuppressWarnings("unchecked")
    public void accept(String eventName, String data) throws ClaudeApiException {
        Map<String, Object> event = parse(data);
        String type = event.get("type") instanceof String t ? t : eventName;
        if (type == null) {
            throw new ClaudeApiException("Evento del stream de Claude sin tipo: " + data);
        }
        switch (type) {
            case "ping" -> { }
            case "error" -> {
                Map<String, Object> error = event.get("error") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
                throw new ClaudeApiException("La API de Claude envió un error en el stream: "
                        + error.getOrDefault("type", "?") + ": " + error.getOrDefault("message", data));
            }
            case "message_start" -> {
                Map<String, Object> message = requireMap(event, "message");
                started = true;
                messageId = message.get("id") instanceof String id ? id : null;
                if (message.get("usage") instanceof Map<?, ?> startUsage) {
                    usage.putAll((Map<String, Object>) startUsage);
                }
                if (message.get("container") != null) {
                    container = message.get("container");
                }
            }
            case "content_block_start" -> {
                requireStarted(type);
                int index = requireIndex(event);
                blocks.put(index, new LinkedHashMap<>(requireMap(event, "content_block")));
            }
            case "content_block_delta" -> applyDelta(requireBlock(event), requireIndex(event), requireMap(event, "delta"));
            case "content_block_stop" -> finishBlock(requireBlock(event), requireIndex(event));
            case "message_delta" -> {
                requireStarted(type);
                Map<String, Object> delta = requireMap(event, "delta");
                if (delta.containsKey("stop_reason")) {
                    stopReason = delta.get("stop_reason");
                }
                if (delta.get("container") != null) {
                    container = delta.get("container");
                }
                // el usage de message_delta es acumulado: sus campos pisan a los de message_start
                if (event.get("usage") instanceof Map<?, ?> deltaUsage) {
                    usage.putAll((Map<String, Object>) deltaUsage);
                }
            }
            case "message_stop" -> {
                requireStarted(type);
                stopped = true;
            }
            default -> { } // eventos nuevos que no cambian el mensaje: se ignoran
        }
    }

    /** La respuesta completa; falla si el stream no llegó a message_stop. */
    public Map<String, Object> result() throws ClaudeApiException {
        if (!stopped) {
            throw new ClaudeApiException("El stream de Claude terminó antes de message_stop (respuesta incompleta).");
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", messageId);
        response.put("content", new ArrayList<>(blocks.values()));
        response.put("stop_reason", stopReason);
        response.put("usage", usage);
        if (container != null) {
            response.put("container", container);
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    private void applyDelta(Map<String, Object> block, int index, Map<String, Object> delta) {
        String deltaType = delta.get("type") instanceof String t ? t : "";
        switch (deltaType) {
            case "text_delta" -> block.put("text", stringOf(block.get("text")) + stringOf(delta.get("text")));
            case "citations_delta" -> {
                List<Object> citations = block.get("citations") instanceof List<?> existing
                        ? (List<Object>) existing : new ArrayList<>();
                citations = new ArrayList<>(citations);
                citations.add(delta.get("citation"));
                block.put("citations", citations);
            }
            case "input_json_delta" -> partialJson.computeIfAbsent(index, i -> new StringBuilder())
                    .append(stringOf(delta.get("partial_json")));
            case "thinking_delta" -> block.put("thinking", stringOf(block.get("thinking")) + stringOf(delta.get("thinking")));
            case "signature_delta" -> block.put("signature", delta.get("signature"));
            default -> throw new ClaudeApiException("Tipo de delta desconocido en el stream de Claude: '" + deltaType
                    + "'; no se puede reconstruir el bloque de forma segura.");
        }
    }

    private void finishBlock(Map<String, Object> block, int index) {
        StringBuilder json = partialJson.remove(index);
        if (json != null && !json.toString().isBlank()) {
            block.put("input", parse(json.toString()));
        }
    }

    private Map<String, Object> requireBlock(Map<String, Object> event) {
        requireStarted(String.valueOf(event.get("type")));
        int index = requireIndex(event);
        Map<String, Object> block = blocks.get(index);
        if (block == null) {
            throw new ClaudeApiException("El stream de Claude envió " + event.get("type") + " para el bloque " + index
                    + " sin content_block_start.");
        }
        return block;
    }

    private void requireStarted(String type) {
        if (!started) {
            throw new ClaudeApiException("El stream de Claude envió " + type + " antes de message_start.");
        }
    }

    private static int requireIndex(Map<String, Object> event) {
        if (event.get("index") instanceof Number n) {
            return n.intValue();
        }
        throw new ClaudeApiException("Evento del stream de Claude sin 'index': " + event);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireMap(Map<String, Object> event, String field) {
        if (event.get(field) instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new ClaudeApiException("Evento '" + event.get("type") + "' del stream de Claude sin '" + field + "'.");
    }

    private Map<String, Object> parse(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            throw new ClaudeApiException("JSON inválido en el stream de Claude: " + json, e);
        }
    }

    private static String stringOf(Object value) {
        return value == null ? "" : value.toString();
    }
}
