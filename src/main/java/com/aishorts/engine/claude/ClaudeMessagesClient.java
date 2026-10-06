package com.aishorts.engine.claude;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cliente de bajo nivel para la Messages API de Claude, compartido por
 * cualquier servicio de este proyecto que necesite pedirle texto al modelo
 * (guionado, traducción de subtítulos, ...). No sabe nada del formato de
 * respuesta que cada consumidor espera — solo manda el mensaje y devuelve el
 * texto plano de la respuesta; el parseo del contenido (JSON de escenas,
 * JSON de traducciones, etc.) es responsabilidad de quien llama.
 */
public final class ClaudeMessagesClient {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ClaudeConfig config;
    private final HttpClient http;
    private final ObjectMapper objectMapper;

    public ClaudeMessagesClient(ClaudeConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    /**
     * Igual que el constructor de arriba pero fijando la versión de HTTP.
     * HTTP/1.1 evita problemas de HTTP/2 con proxies de inspección TLS
     * corporativos en llamadas largas (por ejemplo, con búsqueda web).
     */
    public ClaudeMessagesClient(ClaudeConfig config, ObjectMapper objectMapper, HttpClient.Version httpVersion) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).version(httpVersion).build();
    }

    public String sendMessage(String systemPrompt, String userMessage) throws ClaudeApiException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("max_tokens", config.maxTokens());
        body.put("system", systemPrompt);
        body.put("messages", List.of(Map.of("role", "user", "content", userMessage)));

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/v1/messages"))
                    .header("x-api-key", config.apiKey())
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new ClaudeApiException("La API de Claude respondió " + response.statusCode() + ": " + response.body());
            }
            Map<String, Object> json = objectMapper.readValue(response.body(), MAP_TYPE);
            Object contentObj = json.get("content");
            if (!(contentObj instanceof List<?> contentList) || contentList.isEmpty()) {
                throw new ClaudeApiException("La respuesta de Claude no trae 'content'. Cruda: " + response.body());
            }
            for (Object entry : contentList) {
                if (entry instanceof Map<?, ?> block && "text".equals(block.get("type"))
                        && block.get("text") instanceof String text) {
                    return text;
                }
            }
            throw new ClaudeApiException("Ningún bloque de 'content' es de tipo texto. Cruda: " + response.body());
        } catch (IOException e) {
            throw new ClaudeApiException("Error de red llamando a la API de Claude", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClaudeApiException("Llamada a la API de Claude interrumpida", e);
        }
    }

    /**
     * Manda un body completo a /v1/messages (con tools, varios mensajes,
     * etc.) y devuelve la respuesta JSON entera, sin interpretar: los
     * bloques de contenido que no son texto (server_tool_use,
     * web_search_tool_result, citas, ...) quedan intactos para que quien
     * llama pueda reenviarlos tal cual (por ejemplo, tras un pause_turn).
     * Completa model y max_tokens con los de ClaudeConfig si el body no los
     * trae. A diferencia de sendMessage, aplica un timeout de lectura: una
     * búsqueda web puede tardar, pero no indefinidamente.
     */
    public Map<String, Object> createMessage(Map<String, Object> body, Duration timeout) throws ClaudeApiException {
        Map<String, Object> fullBody = new LinkedHashMap<>();
        fullBody.put("model", config.model());
        fullBody.put("max_tokens", config.maxTokens());
        fullBody.putAll(body);

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/v1/messages"))
                    .timeout(timeout)
                    .header("x-api-key", config.apiKey())
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(fullBody)))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new ClaudeHttpException(response.statusCode(), response.body());
            }
            return objectMapper.readValue(response.body(), MAP_TYPE);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ClaudeApiException("La API de Claude no respondió dentro de " + timeout.toSeconds() + " s", e);
        } catch (IOException e) {
            throw new ClaudeApiException("Error de red llamando a la API de Claude", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClaudeApiException("Llamada a la API de Claude interrumpida", e);
        }
    }

    /**
     * Los modelos a veces envuelven el JSON pedido en un bloque de código
     * markdown pese a que se les pide que no lo hagan. Lo usan tanto el
     * guionado como la traducción de subtítulos, para no duplicar el mismo
     * saneo en cada consumidor.
     */
    public static String stripMarkdownFence(String text) {
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline != -1 && lastFence > firstNewline) {
                return text.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return text;
    }
}
