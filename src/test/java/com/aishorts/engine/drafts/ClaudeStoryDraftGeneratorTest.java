package com.aishorts.engine.drafts;

import com.aishorts.engine.claude.ClaudeConfig;
import com.aishorts.engine.drafts.StoryDraftGenerator.GeneratorRequest;
import com.aishorts.engine.drafts.StoryDraftGenerator.GeneratorResult;
import com.aishorts.engine.drafts.StoryDraftGenerator.Mode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ClaudeStoryDraftGenerator contra un servidor HTTP LOCAL que imita la
 * Messages API (mismo enfoque que ClaudeMessagesClientTest): nunca sale a
 * la red real. Cubre pause_turn (reenviando los bloques intactos), la
 * recolección de URLs de resultados de búsqueda y citas, el uso acumulado,
 * el 400 de "web search no habilitada" y el fallback de versión del tool.
 */
class ClaudeStoryDraftGeneratorTest {

    private HttpServer server;
    private final Deque<int[]> statuses = new ArrayDeque<>();
    private final Deque<String> bodies = new ArrayDeque<>();
    private final List<Map<String, Object>> receivedRequests = new ArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", this::route);
        server.start();
    }

    @AfterEach
    void stopServer() {
        created.forEach(ClaudeStoryDraftGenerator::close);
        server.stop(0);
    }

    private final List<ClaudeStoryDraftGenerator> created = new ArrayList<>();

    private ClaudeStoryDraftGenerator generator() {
        return generator(DraftFixtures.properties());
    }

    private ClaudeStoryDraftGenerator generator(DraftsProperties properties) {
        ClaudeConfig config = new ClaudeConfig("test-key", "test-model", "http://127.0.0.1:" + server.getAddress().getPort(), 16000);
        // la misma fábrica que usa DraftsConfiguration en producción
        ClaudeStoryDraftGenerator generator = ClaudeStoryDraftGenerator.create(config, properties);
        created.add(generator);
        return generator;
    }

    private void respond(int status, String body) {
        statuses.add(new int[]{status});
        bodies.add(body);
    }

    private static GeneratorRequest draftRequest() {
        return new GeneratorRequest(Mode.DRAFT, "reglas", "MODO: BORRADOR", true, 10);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pauseTurn_resendsAssistantBlocksIntact_andCollectsUrlsAndUsage() throws IOException {
        // "campo_futuro": un campo que el SDK no modela también tiene que volver tal cual.
        String firstResponse = """
                {"stop_reason":"pause_turn","container":{"id":"cont_1"},
                 "usage":{"input_tokens":100,"output_tokens":10,"server_tool_use":{"web_search_requests":1}},
                 "content":[
                  {"type":"text","text":"Voy a buscar. "},
                  {"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{"query":"tunguska"},"campo_futuro":"F1"},
                  {"type":"web_search_tool_result","tool_use_id":"srvtoolu_1","content":[
                    {"type":"web_search_result","url":"https://science.nasa.gov/a","title":"A","encrypted_content":"ENC-AAA","page_age":null,"campo_futuro":"F2"}]}
                 ]}""";
        respond(200, firstResponse);
        respond(200, """
                {"stop_reason":"end_turn",
                 "usage":{"input_tokens":200,"output_tokens":50,"server_tool_use":{"web_search_requests":2}},
                 "content":[
                  {"type":"text","text":"{\\"id\\":\\"x\\","},
                  {"type":"text","text":"\\"topic\\":\\"t\\"}","citations":[
                    {"type":"web_search_result_location","url":"https://www.britannica.com/b","title":"B","encrypted_index":"IDX","cited_text":"..."}]}
                 ]}""");

        GeneratorResult result = generator().generate(draftRequest());

        assertThat(result.text()).isEqualTo("Voy a buscar. {\"id\":\"x\",\"topic\":\"t\"}");
        assertThat(result.researchedUrls()).containsExactly("https://science.nasa.gov/a", "https://www.britannica.com/b");
        assertThat(result.usage()).isEqualTo(new DraftUsage(300, 60, 3));
        assertThat(result.stopReason()).isEqualTo("end_turn");
        assertThat(result.calls()).isEqualTo(2);
        assertThat(result.blockCounts()).isEqualTo(Map.of("text", 3, "server_tool_use", 1, "web_search_tool_result", 1));

        assertThat(receivedRequests).hasSize(2);
        Map<String, Object> first = receivedRequests.get(0);
        assertThat(first.get("model")).isEqualTo("test-model");
        // app.drafts.max-tokens (64000), no el 16000 de ClaudeConfig
        assertThat(first.get("max_tokens")).isEqualTo(64000);
        assertThat(receivedRequests.get(1).get("max_tokens")).isEqualTo(64000);
        assertThat(first.get("tools")).isEqualTo(List.of(Map.of("type", "web_search_20260318", "name", "web_search", "max_uses", 10)));
        Map<String, Object> continuation = receivedRequests.get(1);
        assertThat(continuation.get("container")).isEqualTo("cont_1");
        assertThat(continuation.get("tools")).isEqualTo(first.get("tools"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) continuation.get("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).get("role")).isEqualTo("assistant");
        // Cada bloque vuelve con TODOS sus campos y valores originales (anidados incluidos).
        List<Object> original = (List<Object>) objectMapper.readValue(firstResponse, Map.class).get("content");
        List<Object> resent = (List<Object>) messages.get(1).get("content");
        assertThat(resent).hasSameSizeAs(original);
        for (int i = 0; i < original.size(); i++) {
            assertKeptIntact(original.get(i), resent.get(i), "content[" + i + "]");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void pauseTurn_serverToolUseWithInputInStartAndNoDeltas_doesNotFail_andIsResentIntact() throws IOException {
        // índice 2: server_tool_use con el input completo en content_block_start y sin deltas
        respond(200, """
                {"stop_reason":"pause_turn","container":{"id":"cont_1"},
                 "usage":{"input_tokens":100,"output_tokens":10,"server_tool_use":{"web_search_requests":1}},
                 "content":[
                  {"type":"text","text":"Voy a buscar. "},
                  {"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{"query":"tunguska"}},
                  {"type":"server_tool_use","id":"srvtoolu_2","name":"code_execution","_inputInStart":true,
                   "input":{"code":"print(len(results))","nested":{"a":[1,2],"b":null}},"campo_futuro":"F1"}
                 ]}""");
        respond(200, """
                {"stop_reason":"end_turn","usage":{"input_tokens":200,"output_tokens":50},
                 "content":[{"type":"text","text":"{}"}]}""");

        GeneratorResult result = generator().generate(draftRequest());

        assertThat(result.stopReason()).isEqualTo("end_turn");
        List<Map<String, Object>> messages = (List<Map<String, Object>>) receivedRequests.get(1).get("messages");
        List<Object> resent = (List<Object>) messages.get(1).get("content");
        assertThat(resent).hasSize(3);
        Map<String, Object> expected = objectMapper.readValue("""
                {"type":"server_tool_use","id":"srvtoolu_2","name":"code_execution",
                 "input":{"code":"print(len(results))","nested":{"a":[1,2],"b":null}},"campo_futuro":"F1"}""", Map.class);
        assertKeptIntact(expected, resent.get(2), "content[2]");
        assertThat(((Map<String, Object>) resent.get(2)).get("input")).isEqualTo(expected.get("input")); // ni una clave de más
        assertKeptIntact(Map.of("input", Map.of("query", "tunguska")), resent.get(1), "content[1]");
    }

    @Test
    void thinkingOnlyResponseCutByMaxTokens_returnsNoText_withItsStopReasonAndBlocks() {
        // la forma de (B): el presupuesto se va en thinking (display omitted: texto vacío) y no llega texto
        respond(200, """
                {"stop_reason":"max_tokens","usage":{"input_tokens":100,"output_tokens":64000},
                 "content":[{"type":"thinking","thinking":"","signature":"sig-1"}]}""");

        GeneratorResult result = generator().generate(draftRequest());

        assertThat(result.text()).isEmpty();
        assertThat(result.stopReason()).isEqualTo("max_tokens");
        assertThat(result.calls()).isEqualTo(1);
        assertThat(result.blockCounts()).isEqualTo(Map.of("thinking", 1));
        assertThat(result.usage().outputTokens()).isEqualTo(64000);
    }

    /** Todo lo que trae {@code original} está en {@code resent} con el mismo valor (puede haber campos de más). */
    private static void assertKeptIntact(Object original, Object resent, String path) {
        if (original instanceof Map<?, ?> o) {
            assertThat(resent).as(path).isInstanceOf(Map.class);
            Map<?, ?> r = (Map<?, ?>) resent;
            for (Map.Entry<?, ?> e : o.entrySet()) {
                assertThat(r.containsKey(e.getKey())).as(path + "." + e.getKey() + " falta").isTrue();
                assertKeptIntact(e.getValue(), r.get(e.getKey()), path + "." + e.getKey());
            }
        } else if (original instanceof List<?> o) {
            assertThat(resent).as(path).isInstanceOf(List.class);
            assertThat((List<?>) resent).as(path).hasSameSizeAs(o);
            for (int i = 0; i < o.size(); i++) {
                assertKeptIntact(o.get(i), ((List<?>) resent).get(i), path + "[" + i + "]");
            }
        } else {
            assertThat(resent).as(path).isEqualTo(original);
        }
    }

    @Test
    void pauseTurn_beyondTheCap_failsWith502() {
        for (int i = 0; i < 10; i++) {
            respond(200, "{\"stop_reason\":\"pause_turn\",\"content\":[{\"type\":\"text\",\"text\":\".\"}]}");
        }
        assertThatThrownBy(() -> generator().generate(draftRequest()))
                .isInstanceOfSatisfying(DraftException.class, e -> assertThat(e.status()).isEqualTo(502))
                .hasMessageContaining("pause_turn");
        assertThat(receivedRequests).hasSize(DraftFixtures.properties().maxPauseContinuations() + 1);
    }

    @Test
    void webSearchDisabledInOrganization_failsWithAClearMessage() {
        respond(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Web search is not enabled for this organization.\"}}");
        assertThatThrownBy(() -> generator().generate(draftRequest()))
                .isInstanceOfSatisfying(DraftException.class, e -> assertThat(e.status()).isEqualTo(502))
                .hasMessageContaining("búsqueda web no está habilitada")
                .hasMessageContaining("CLAUDE_WEB_SEARCH_ENABLED=false");
        assertThat(receivedRequests).hasSize(1);
    }

    @Test
    void toolVersionRejected_retriesOnceWithFallbackAndDirectCaller() {
        respond(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"This model does not support programmatic tool calling; set allowed_callers to [\\\"direct\\\"]\"}}");
        respond(200, "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"{}\"}]}");

        GeneratorResult result = generator().generate(draftRequest());

        assertThat(result.text()).isEqualTo("{}");
        assertThat(receivedRequests).hasSize(2);
        assertThat(receivedRequests.get(1).get("tools")).isEqualTo(List.of(Map.of(
                "type", "web_search_20250305", "name", "web_search", "max_uses", 10, "allowed_callers", List.of("direct"))));
    }

    @Test
    void otherApiErrors_become502_andWithoutWebSearchNoToolsAreSent() {
        respond(500, "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"boom\"}}");
        GeneratorRequest noTools = new GeneratorRequest(Mode.DRAFT_RETRY, "reglas", "corrige", false, 0);
        assertThatThrownBy(() -> generator().generate(noTools))
                .isInstanceOfSatisfying(DraftException.class, e -> assertThat(e.status()).isEqualTo(502));
        assertThat(receivedRequests.get(0)).doesNotContainKey("tools");
    }

    @Test
    void streamCutBeforeMessageStop_is502_neverAHalfBuiltResponse() {
        respond(RAW_SSE, """
                event: message_start
                data: {"type":"message_start","message":{"id":"m","content":[],"usage":{"input_tokens":5,"output_tokens":1}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"{\\"id\\":"}}

                """);
        assertThatThrownBy(() -> generator().generate(draftRequest()))
                .isInstanceOfSatisfying(DraftException.class, e -> assertThat(e.status()).isEqualTo(502))
                .hasMessageContaining("message_stop");
    }

    @Test
    void errorEventMidStream_is502WithTheApiError() {
        respond(RAW_SSE, """
                event: message_start
                data: {"type":"message_start","message":{"id":"m","content":[],"usage":{"input_tokens":5,"output_tokens":1}}}

                event: error
                data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}

                """);
        assertThatThrownBy(() -> generator().generate(draftRequest()))
                .isInstanceOfSatisfying(DraftException.class, e -> assertThat(e.status()).isEqualTo(502))
                .hasMessageContaining("overloaded_error");
    }

    @Test
    void noEventsForLongerThanTheIdleTimeout_abortsWith502() {
        respond(STALL, "event: ping\ndata: {\"type\":\"ping\"}\n\n");
        long start = System.nanoTime();
        assertThatThrownBy(() -> generator(withReadTimeout(DraftFixtures.properties(), 1)).generate(draftRequest()))
                .isInstanceOfSatisfying(DraftException.class, e -> assertThat(e.status()).isEqualTo(502))
                .hasMessageContaining("no envió eventos durante 1 s");
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofMillis(2_800));
    }

    @Test
    void streamLongerThanTheTimeoutInTotal_butWithRegularEvents_completes() {
        respond(SLOW_SSE, "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"{\\\"id\\\":\\\"lento\\\"}\"}]}");
        long start = System.nanoTime();
        GeneratorResult result = generator(withReadTimeout(DraftFixtures.properties(), 1)).generate(draftRequest());
        assertThat(result.text()).isEqualTo("{\"id\":\"lento\"}");
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isGreaterThan(java.time.Duration.ofMillis(1_500));
    }

    @Test
    void requestsAskForStreaming() {
        respond(200, "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"{}\"}]}");
        generator().generate(draftRequest());
        assertThat(receivedRequests.get(0).get("stream")).isEqualTo(true);
    }

    private static DraftsProperties withReadTimeout(DraftsProperties p, int seconds) {
        return new DraftsProperties(p.dir(), p.rulesResource(), p.webSearch(), seconds, p.maxPauseContinuations(), p.maxTokens(),
                p.wordsPerSecond(), p.minTotalWords(), p.maxTotalWords(), p.minSceneSeconds(), p.maxSceneSeconds(),
                p.sceneWordRanges(), p.styleAnchor(), p.forbiddenVisualTerms(), p.negativePromptTerms(),
                p.slowRevealTerms(), p.absoluteTerms(), p.tierADomains(), p.tierBDomains(), p.orientationOnlyDomains());
    }

    @Test
    void connectionDroppedMidCall_the502CarriesTheRootCause() {
        respond(DROP_CONNECTION, "");
        assertThatThrownBy(() -> generator().generate(draftRequest()))
                .isInstanceOfSatisfying(DraftException.class, e -> assertThat(e.status()).isEqualTo(502))
                .hasMessageContaining("Error de red")
                .hasMessageContaining("| causa: java.io.");
    }

    @Test
    void requestsUseHttp11_neverTryToUpgradeToHttp2() {
        respond(200, "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"{}\"}]}");
        generator().generate(draftRequest());
        assertThat(receivedUpgradeHeaders).containsExactly("(ninguno)");
        assertThat(receivedProtocols).containsExactly("HTTP/1.1");
    }

    @Test
    void requestsCarryTheApiKeyAndApiVersion_throughTheReplacedTransport() {
        respond(200, "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"{}\"}]}");
        generator().generate(draftRequest());
        assertThat(receivedApiKeys).containsExactly(List.of("test-key"));
        assertThat(receivedApiVersions).containsExactly("2023-06-01");
    }

    private static final int DROP_CONNECTION = -1;
    private final List<String> receivedUpgradeHeaders = new ArrayList<>();
    private final List<String> receivedProtocols = new ArrayList<>();
    private final List<List<String>> receivedApiKeys = new ArrayList<>();
    private final List<String> receivedApiVersions = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private void route(HttpExchange exchange) throws IOException {
        try {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            receivedRequests.add(objectMapper.readValue(requestBody, Map.class));
            String upgrade = exchange.getRequestHeaders().getFirst("Upgrade");
            receivedUpgradeHeaders.add(upgrade != null ? upgrade : "(ninguno)");
            receivedProtocols.add(exchange.getProtocol());
            receivedApiKeys.add(exchange.getRequestHeaders().getOrDefault("x-api-key", List.of()));
            receivedApiVersions.add(exchange.getRequestHeaders().getFirst("anthropic-version"));
            int[] status = statuses.poll();
            String body = bodies.poll();
            if (status != null && status[0] == DROP_CONNECTION) {
                return; // cierra sin responder: el cliente ve la conexión cortada
            }
            if (status == null) {
                status = new int[]{500};
                body = "{\"error\":\"sin respuesta encolada\"}";
            }
            if (status[0] == SLOW_SSE) {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    for (String event : toSse(body).split("(?<=\n\n)")) {
                        out.write(event.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        sleepQuietly(200); // cada evento llega antes del timeout de inactividad (1 s)
                    }
                }
                return;
            }
            if (status[0] == RAW_SSE || status[0] == STALL) {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                OutputStream out = exchange.getResponseBody();
                out.write(body.getBytes(StandardCharsets.UTF_8));
                out.flush();
                if (status[0] == STALL) {
                    sleepQuietly(3_000); // headers y un ping, después silencio
                }
                return; // se cierra sin message_stop (o tras el silencio)
            }
            if (status[0] == 200) {
                // la API real responde en SSE porque el cliente pide "stream": true
                body = toSse(body);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            } else {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status[0], bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } finally {
            exchange.close();
        }
    }

    private static final int RAW_SSE = -2;
    private static final int STALL = -3;
    private static final int SLOW_SSE = -4;

    /**
     * Convierte un mensaje completo (como lo devolvería la API sin streaming)
     * en los eventos SSE que manda la API real con "stream": true: el texto
     * partido en text_delta, las citas como citations_delta, el input de
     * server_tool_use como input_json_delta, los demás bloques enteros en
     * content_block_start, y pings intercalados.
     */
    @SuppressWarnings("unchecked")
    private String toSse(String messageJson) throws IOException {
        Map<String, Object> message = objectMapper.readValue(messageJson, Map.class);
        Map<String, Object> usage = (Map<String, Object>) message.getOrDefault("usage", Map.of());
        StringBuilder sse = new StringBuilder();
        Map<String, Object> start = new java.util.LinkedHashMap<>();
        start.put("id", "msg_test");
        start.put("type", "message");
        start.put("role", "assistant");
        start.put("content", List.of());
        start.put("stop_reason", null);
        start.put("usage", Map.of("input_tokens", usage.getOrDefault("input_tokens", 0), "output_tokens", 1));
        if (message.get("container") != null) {
            start.put("container", message.get("container"));
        }
        event(sse, "message_start", Map.of("type", "message_start", "message", start));
        event(sse, "ping", Map.of("type", "ping"));

        List<Map<String, Object>> content = (List<Map<String, Object>>) message.getOrDefault("content", List.of());
        for (int i = 0; i < content.size(); i++) {
            Map<String, Object> block = content.get(i);
            String type = (String) block.get("type");
            if ("text".equals(type)) {
                event(sse, "content_block_start", Map.of("type", "content_block_start", "index", i,
                        "content_block", Map.of("type", "text", "text", "")));
                for (Object citation : (List<Object>) block.getOrDefault("citations", List.of())) {
                    event(sse, "content_block_delta", Map.of("type", "content_block_delta", "index", i,
                            "delta", Map.of("type", "citations_delta", "citation", citation)));
                }
                String text = (String) block.get("text");
                int half = text.length() / 2;
                for (String piece : List.of(text.substring(0, half), text.substring(half))) {
                    event(sse, "content_block_delta", Map.of("type", "content_block_delta", "index", i,
                            "delta", Map.of("type", "text_delta", "text", piece)));
                    event(sse, "ping", Map.of("type", "ping"));
                }
            } else if ("server_tool_use".equals(type) && Boolean.TRUE.equals(block.get("_inputInStart"))) {
                // Marca solo del test: el input completo en el start y SIN input_json_delta (lo que
                // rompía MessageAccumulator con "Missing input JSON for index N").
                Map<String, Object> full = new java.util.LinkedHashMap<>(block);
                full.remove("_inputInStart");
                event(sse, "content_block_start", Map.of("type", "content_block_start", "index", i, "content_block", full));
            } else if ("server_tool_use".equals(type)) {
                Map<String, Object> base = new java.util.LinkedHashMap<>(block);
                base.put("input", Map.of());
                event(sse, "content_block_start", Map.of("type", "content_block_start", "index", i, "content_block", base));
                String input = objectMapper.writeValueAsString(block.get("input"));
                int half = input.length() / 2;
                for (String piece : List.of(input.substring(0, half), input.substring(half))) {
                    event(sse, "content_block_delta", Map.of("type", "content_block_delta", "index", i,
                            "delta", Map.of("type", "input_json_delta", "partial_json", piece)));
                }
            } else {
                event(sse, "content_block_start", Map.of("type", "content_block_start", "index", i, "content_block", block));
            }
            event(sse, "content_block_stop", Map.of("type", "content_block_stop", "index", i));
        }
        Map<String, Object> delta = new java.util.LinkedHashMap<>();
        delta.put("stop_reason", message.get("stop_reason"));
        event(sse, "message_delta", Map.of("type", "message_delta", "delta", delta, "usage", usage));
        event(sse, "message_stop", Map.of("type", "message_stop"));
        return sse.toString();
    }

    private void event(StringBuilder sse, String name, Object data) throws IOException {
        sse.append("event: ").append(name).append('\n')
                .append("data: ").append(objectMapper.writeValueAsString(data)).append("\n\n");
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
