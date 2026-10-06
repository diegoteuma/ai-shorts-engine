package com.aishorts.engine.drafts;

import com.aishorts.engine.claude.ClaudeConfig;
import com.aishorts.engine.claude.ClaudeMessagesClient;
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
        server.stop(0);
    }

    private ClaudeStoryDraftGenerator generator() {
        ClaudeConfig config = new ClaudeConfig("test-key", "test-model", "http://127.0.0.1:" + server.getAddress().getPort(), 16000);
        return new ClaudeStoryDraftGenerator(new ClaudeMessagesClient(config, objectMapper), DraftFixtures.properties());
    }

    private void respond(int status, String body) {
        statuses.add(new int[]{status});
        bodies.add(body);
    }

    private static GeneratorRequest draftRequest() {
        return new GeneratorRequest(Mode.DRAFT, "reglas", "MODO: BORRADOR", true, 10);
    }

    @Test
    void pauseTurn_resendsAssistantBlocksIntact_andCollectsUrlsAndUsage() {
        respond(200, """
                {"stop_reason":"pause_turn","container":{"id":"cont_1"},
                 "usage":{"input_tokens":100,"output_tokens":10,"server_tool_use":{"web_search_requests":1}},
                 "content":[
                  {"type":"text","text":"Voy a buscar. "},
                  {"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{"query":"tunguska"}},
                  {"type":"web_search_tool_result","tool_use_id":"srvtoolu_1","content":[
                    {"type":"web_search_result","url":"https://science.nasa.gov/a","title":"A","encrypted_content":"ENC-AAA","page_age":null}]}
                 ]}""");
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

        assertThat(receivedRequests).hasSize(2);
        Map<String, Object> first = receivedRequests.get(0);
        assertThat(first.get("model")).isEqualTo("test-model");
        assertThat(first.get("tools")).isEqualTo(List.of(Map.of("type", "web_search_20260318", "name", "web_search", "max_uses", 10)));
        Map<String, Object> continuation = receivedRequests.get(1);
        assertThat(continuation.get("container")).isEqualTo("cont_1");
        assertThat(continuation.get("tools")).isEqualTo(first.get("tools"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) continuation.get("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).get("role")).isEqualTo("assistant");
        String resent = messages.get(1).get("content").toString();
        assertThat(resent).contains("ENC-AAA").contains("srvtoolu_1").contains("Voy a buscar.");
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

    @SuppressWarnings("unchecked")
    private void route(HttpExchange exchange) throws IOException {
        try {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            receivedRequests.add(objectMapper.readValue(requestBody, Map.class));
            int[] status = statuses.poll();
            String body = bodies.poll();
            if (status == null) {
                status = new int[]{500};
                body = "{\"error\":\"sin respuesta encolada\"}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status[0], bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } finally {
            exchange.close();
        }
    }
}
