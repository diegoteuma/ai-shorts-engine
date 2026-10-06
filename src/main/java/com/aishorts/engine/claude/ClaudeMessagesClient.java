package com.aishorts.engine.claude;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

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
     * etc.) con "stream": true y devuelve la respuesta reconstruida
     * ({content, stop_reason, usage, container}), con los bloques que no son
     * texto (server_tool_use, web_search_tool_result, citas, ...) intactos
     * para poder reenviarlos tal cual tras un pause_turn. Completa model y
     * max_tokens con los de ClaudeConfig si el body no los trae.
     *
     * Por qué streaming: una llamada con búsqueda web tarda minutos, y sin
     * streaming la conexión pasa todo ese tiempo sin tráfico; en una red con
     * inspección TLS se observó que la cortaban (Connection reset). Con
     * streaming llegan eventos y pings mientras el modelo trabaja.
     *
     * @param idleTimeout tiempo máximo sin recibir NINGÚN evento (ni ping),
     *                    y también para recibir los headers; no limita la
     *                    duración total mientras sigan llegando eventos.
     */
    public Map<String, Object> createMessageStreaming(Map<String, Object> body, Duration idleTimeout) throws ClaudeApiException {
        Map<String, Object> fullBody = new LinkedHashMap<>();
        fullBody.put("model", config.model());
        fullBody.put("max_tokens", config.maxTokens());
        fullBody.putAll(body);
        fullBody.put("stream", true);

        AtomicLong lastActivity = new AtomicLong(System.nanoTime());
        ExecutorService readerThread = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "claude-stream-reader");
            thread.setDaemon(true);
            return thread;
        });
        try {
            // Sin HttpRequest.timeout a propósito: con ofInputStream ese timeout
            // también corta la lectura del cuerpo, o sea, limitaría la duración
            // TOTAL del stream. La espera de los headers se acota acá y la del
            // cuerpo, por inactividad, más abajo.
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/v1/messages"))
                    .header("x-api-key", config.apiKey())
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(fullBody)))
                    .build();

            CompletableFuture<HttpResponse<InputStream>> pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            HttpResponse<InputStream> response;
            try {
                response = pending.get(idleTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException noHeaders) {
                pending.cancel(true);
                throw new ClaudeApiException("La API de Claude no respondió dentro de " + idleTimeout.toSeconds() + " s", noHeaders);
            } catch (ExecutionException failed) {
                if (failed.getCause() instanceof IOException ioError) {
                    throw ioError;
                }
                throw new ClaudeApiException("Error llamando a la API de Claude", failed.getCause());
            }
            if (response.statusCode() / 100 != 2) {
                try (InputStream errorBody = response.body()) {
                    throw new ClaudeHttpException(response.statusCode(), new String(errorBody.readAllBytes(), StandardCharsets.UTF_8));
                }
            }

            // La lectura va en su propio hilo: este hilo vigila la inactividad y,
            // si se pasa, interrumpe la lectura (un read bloqueado del body de
            // HttpClient no se desbloquea de forma confiable cerrando el stream).
            InputStream in = response.body();
            Future<Map<String, Object>> reading = readerThread.submit(() -> readEvents(in, lastActivity));
            long idleNanos = idleTimeout.toNanos();
            long pollMillis = Math.max(50, Math.min(1000, idleTimeout.toMillis() / 4));
            while (true) {
                try {
                    return reading.get(pollMillis, TimeUnit.MILLISECONDS);
                } catch (TimeoutException stillReading) {
                    if (System.nanoTime() - lastActivity.get() > idleNanos) {
                        reading.cancel(true);
                        closeQuietly(in);
                        throw new ClaudeApiException("La API de Claude no envió eventos durante " + idleTimeout.toSeconds() + " s");
                    }
                } catch (ExecutionException failed) {
                    Throwable cause = failed.getCause();
                    if (cause instanceof ClaudeApiException apiError) {
                        throw apiError;
                    }
                    if (cause instanceof IOException ioError) {
                        throw new ClaudeApiException("Error de red leyendo el stream de la API de Claude", ioError);
                    }
                    throw new ClaudeApiException("Error leyendo el stream de la API de Claude", cause);
                }
            }
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ClaudeApiException("La API de Claude no respondió dentro de " + idleTimeout.toSeconds() + " s", e);
        } catch (IOException e) {
            throw new ClaudeApiException("Error de red llamando a la API de Claude", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClaudeApiException("Llamada a la API de Claude interrumpida", e);
        } finally {
            readerThread.shutdownNow();
        }
    }

    /** Lee el SSE línea por línea, registrando actividad, y devuelve la respuesta reconstruida. */
    private Map<String, Object> readEvents(InputStream in, AtomicLong lastActivity) throws IOException {
        ClaudeStreamAccumulator accumulator = new ClaudeStreamAccumulator(objectMapper);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String eventName = null;
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                lastActivity.set(System.nanoTime());
                if (line.isEmpty()) {
                    if (!data.isEmpty()) {
                        accumulator.accept(eventName, data.toString());
                    }
                    eventName = null;
                    data.setLength(0);
                } else if (line.startsWith(":")) {
                    // comentario SSE
                } else if (line.startsWith("event:")) {
                    eventName = line.substring("event:".length()).strip();
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    String value = line.substring("data:".length());
                    data.append(value.startsWith(" ") ? value.substring(1) : value);
                }
            }
            if (!data.isEmpty()) {
                accumulator.accept(eventName, data.toString());
            }
        }
        return accumulator.result();
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // ya cerrado
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
