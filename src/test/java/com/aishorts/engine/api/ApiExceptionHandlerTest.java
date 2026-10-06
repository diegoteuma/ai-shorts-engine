package com.aishorts.engine.api;

import com.aishorts.engine.script.ScriptDraftingException;
import com.aishorts.engine.script.ScriptDraftingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Confirma que un error 500 real -- ScriptDraftingException envolviendo una
 * causa original (como haría ClaudeScriptDraftingService con un
 * ClaudeApiException) -- llega al cliente HTTP con el mensaje de la causa
 * original, no solo el texto genérico de la excepción de más afuera.
 *
 * POST /stories solo depende de ScriptDraftingService (ver
 * StoryDraftingService.draftStory), así que alcanza con reemplazar ese único
 * bean; los demás beans "reales" (Higgsfield, TTS) quedan
 * construidos con credenciales dummy pero nunca se invocan en este test.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ApiExceptionHandlerTest.FailingScriptDraftingConfig.class)
class ApiExceptionHandlerTest {

    private static final String ROOT_CAUSE_MESSAGE = "Claude respondió 529 Overloaded: {\"type\":\"error\"}";

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void engineProperties(DynamicPropertyRegistry registry) {
        registry.add("DATA_DIR", () -> dataDir.resolve("stories").toString());
        registry.add("AUDIO_DIR", () -> dataDir.resolve("audio").toString());
        registry.add("CLAUDE_API_KEY", () -> "test-claude-key");
        registry.add("CLAUDE_MODEL", () -> "test-claude-model");
        registry.add("HIGGSFIELD_BASE_URL", () -> "https://higgsfield.invalid");
        registry.add("HIGGSFIELD_API_KEY_ID", () -> "test-id");
        registry.add("HIGGSFIELD_API_KEY_SECRET", () -> "test-secret");
        registry.add("HIGGSFIELD_MODEL_STANDARD", () -> "higgsfield-ai/soul/standard");
        registry.add("HIGGSFIELD_MODEL_PREMIUM", () -> "higgsfield-ai/soul-premium/cinema");
        registry.add("ELEVENLABS_API_KEY", () -> "test-elevenlabs-key");
        registry.add("ELEVENLABS_VOICE_ID", () -> "test-voice");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void a500ResponseIncludesTheOriginalCauseMessageNotJustTheGenericWrapperText() {
        Map<String, Object> createBody = Map.of(
                "storyId", "story-failing-" + System.nanoTime(),
                "topic", "Tema de prueba",
                "title", "Título de prueba",
                "coreFacts", List.of("Un hecho cualquiera."),
                "durationBudget", Map.of("minSeconds", 20, "maxSeconds", 60)
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/stories", createBody, Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        // El wrapper genérico sigue estando en "error"...
        assertThat(body.get("error")).asString()
                .contains("Error llamando a la API de Claude para el guion");
        // ...pero ahora el mensaje real de la causa original es diagnosticable
        // directo desde la respuesta, sin ir a buscar logs.
        assertThat(body.get("error")).asString().contains(ROOT_CAUSE_MESSAGE);
        assertThat(body.get("cause")).isEqualTo(ROOT_CAUSE_MESSAGE);
    }

    @TestConfiguration
    static class FailingScriptDraftingConfig {

        @Bean
        @Primary
        ScriptDraftingService failingScriptDraftingService() {
            return brief -> {
                throw new ScriptDraftingException(
                        "Error llamando a la API de Claude para el guion: " + ROOT_CAUSE_MESSAGE,
                        new IOException(ROOT_CAUSE_MESSAGE));
            };
        }
    }
}