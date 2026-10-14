package com.aishorts.engine.script;

import com.aishorts.engine.domain.SceneRole;
import com.aishorts.engine.duration.DurationBudget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.retry.support.RetryTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El servicio de guionado con el AnthropicChatModel real de Spring AI contra
 * un servidor HTTP local que hace de /v1/messages (mismo enfoque que
 * HiggsfieldRestClientTest): cubre lo que de verdad se manda (system, user,
 * modelo, max_tokens) y cómo se interpreta lo que vuelve.
 */
class ClaudeScriptDraftingServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> ROLES = List.of("GANCHO", "EXPLICACION", "CONTEXTO", "GIRO", "CONSECUENCIA", "CIERRE");

    private static HttpServer server;
    private static int port;
    private static volatile int status;
    private static volatile String responseBody;
    private static volatile String lastRequest;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", ClaudeScriptDraftingServiceTest::route);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void reset() {
        status = 200;
        responseBody = null;
        lastRequest = null;
    }

    @Test
    void draftsSixScenesAndSendsThePromptsTheModelAndMaxTokens() throws Exception {
        respondWithText(scenesJson(ROLES));

        List<SceneDraft> drafts = service().draftScenes(brief("La explosión de {Halifax} en 1917"));

        assertThat(drafts).extracting(SceneDraft::role).containsExactly(SceneRole.GANCHO, SceneRole.EXPLICACION,
                SceneRole.CONTEXTO, SceneRole.GIRO, SceneRole.CONSECUENCIA, SceneRole.CIERRE);
        assertThat(drafts.get(0).narrationText()).isEqualTo("narración GANCHO");
        assertThat(drafts.get(5).visualPrompt()).isEqualTo("visual CIERRE");

        JsonNode request = MAPPER.readTree(lastRequest);
        assertThat(request.get("model").asText()).isEqualTo("test-model");
        assertThat(request.get("max_tokens").asInt()).isEqualTo(16000);
        assertThat(request.get("system").toString()).contains("6 escenas").contains("no inventar cifras");
        String user = request.get("messages").get(0).get("content").toString();
        // Los hechos llegan tal cual (las llaves no se interpretan como plantilla) y con el esquema JSON pedido.
        assertThat(user).contains("La explosión de {Halifax} en 1917").contains("narrationText").contains("$schema");
    }

    @Test
    void usesTheTextBlockWhenThinkingBlocksComeFirst() {
        // Spring AI devuelve cada bloque como una Generation; content() daría el "thinking".
        respondWith(Map.of("type", "thinking", "thinking", "dejame pensar...", "signature", "sig"),
                Map.of("type", "redacted_thinking", "data", "cifrado"),
                Map.of("type", "text", "text", scenesJson(ROLES)));

        assertThat(service().draftScenes(brief("hecho"))).hasSize(6);
    }

    @Test
    void acceptsAMarkdownFenceLowercaseRolesAndExtraKeys() {
        String json = scenesJson(ROLES.stream().map(r -> " " + r.toLowerCase() + " ").toList())
                .replace("\"visualPrompt\"", "\"extra\":1,\"visualPrompt\"");
        respondWithText("```json\n" + json + "\n```");

        assertThat(service().draftScenes(brief("hecho"))).extracting(SceneDraft::role).startsWith(SceneRole.GANCHO);
    }

    @Test
    void rejectsTextThatIsNotJsonAndShowsTheRawText() {
        respondWithText("Lo siento, no puedo.");

        assertThatThrownBy(() -> service().draftScenes(brief("hecho")))
                .isInstanceOf(ScriptDraftingException.class)
                .hasMessageContaining("Texto crudo:\nLo siento, no puedo.");
    }

    @Test
    void rejectsTheWrongNumberOfScenes() {
        respondWithText(scenesJson(ROLES.subList(0, 5)));

        assertThatThrownBy(() -> service().draftScenes(brief("hecho")))
                .isInstanceOf(ScriptDraftingException.class)
                .hasMessageContaining("Se esperaban 6 escenas y llegaron 5");
    }

    @Test
    void rejectsScenesOutOfOrder() {
        respondWithText(scenesJson(List.of("EXPLICACION", "GANCHO", "CONTEXTO", "GIRO", "CONSECUENCIA", "CIERRE")));

        assertThatThrownBy(() -> service().draftScenes(brief("hecho")))
                .isInstanceOf(ScriptDraftingException.class)
                .hasMessageContaining("en la posición 0 llegó EXPLICACION");
    }

    @Test
    void rejectsAnUnknownRole() {
        respondWithText(scenesJson(List.of("INTRO", "EXPLICACION", "CONTEXTO", "GIRO", "CONSECUENCIA", "CIERRE")));

        assertThatThrownBy(() -> service().draftScenes(brief("hecho")))
                .isInstanceOf(ScriptDraftingException.class)
                .hasMessageContaining("Rol de escena desconocido: 'INTRO'");
    }

    @Test
    void wrapsAnApiErrorInAScriptDraftingException() {
        status = 401;
        responseBody = "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}";

        assertThatThrownBy(() -> service().draftScenes(brief("hecho")))
                .isInstanceOf(ScriptDraftingException.class)
                .hasMessageStartingWith("Error llamando a la API de Claude para el guion:")
                .hasMessageContaining("invalid x-api-key");
    }

    private static ClaudeScriptDraftingService service() {
        AnthropicApi api = AnthropicApi.builder().baseUrl("http://127.0.0.1:" + port).apiKey("test-key").build();
        AnthropicChatModel model = AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(AnthropicChatOptions.builder().model("test-model").maxTokens(16000).build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .build();
        return new ClaudeScriptDraftingService(ChatClient.builder(model).build());
    }

    private static StoryBrief brief(String fact) {
        return new StoryBrief("tema", "título", List.of(fact), List.of("no inventar cifras"), DurationBudget.ofSeconds(30, 45));
    }

    private static String scenesJson(List<String> roles) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < roles.size(); i++) {
            String r = roles.get(i).strip();
            sb.append(i > 0 ? "," : "").append("{\"role\":\"").append(roles.get(i))
                    .append("\",\"narrationText\":\"narración ").append(r)
                    .append("\",\"visualPrompt\":\"visual ").append(r).append("\"}");
        }
        return sb.append("]").toString();
    }

    private static void respondWithText(String text) {
        respondWith(Map.of("type", "text", "text", text));
    }

    @SafeVarargs
    private static void respondWith(Map<String, Object>... blocks) {
        try {
            responseBody = MAPPER.writeValueAsString(Map.of(
                    "id", "msg_test", "type", "message", "role", "assistant", "model", "test-model",
                    "content", List.of(blocks), "stop_reason", "end_turn",
                    "usage", Map.of("input_tokens", 10, "output_tokens", 20)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void route(HttpExchange exchange) throws IOException {
        try {
            lastRequest = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } finally {
            exchange.close();
        }
    }
}
