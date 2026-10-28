package com.aishorts.engine.demo;

import com.aishorts.engine.duration.WordsPerSecondDurationEstimator;
import com.aishorts.engine.higgsfield.HiggsfieldClient;
import com.aishorts.engine.script.ScriptDraftingService;
import com.aishorts.engine.tts.TtsService;
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

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Revisar la narración en la PUERTA 1 después de /cost-estimates (o de la
 * PUERTA 2) descarta el audio Y el costo de esa escena: /generate pide el
 * clip con la duración nueva, así que el costo calculado con la vieja ya no
 * vale. Vía HTTP real, con FakeTtsService y FakeHiggsfieldClient.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StoryNarrationRevisionApiTest.FakeServicesConfig.class)
class StoryNarrationRevisionApiTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void engineProperties(DynamicPropertyRegistry registry) {
        registry.add("DATA_DIR", () -> dataDir.resolve("stories").toString());
        registry.add("AUDIO_DIR", () -> dataDir.resolve("audio").toString());
        registry.add("CLIPS_DIR", () -> dataDir.resolve("clips").toString());
        registry.add("CLAUDE_API_KEY", () -> "test-claude-key");
        registry.add("CLAUDE_MODEL", () -> "test-claude-model");
        registry.add("HIGGSFIELD_BASE_URL", () -> "https://higgsfield.invalid");
        registry.add("HIGGSFIELD_API_KEY_ID", () -> "test-id");
        registry.add("HIGGSFIELD_API_KEY_SECRET", () -> "test-secret");
        registry.add("HIGGSFIELD_MODEL_STANDARD",
                () -> com.aishorts.engine.higgsfield.KnownHiggsfieldPricing.STANDARD_MODEL_ID);
        registry.add("HIGGSFIELD_MODEL_PREMIUM",
                () -> com.aishorts.engine.higgsfield.KnownHiggsfieldPricing.PREMIUM_MODEL_ID);
        registry.add("ELEVENLABS_API_KEY", () -> "test-elevenlabs-key");
        registry.add("ELEVENLABS_VOICE_ID", () -> "test-voice");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void revisedNarrationAfterCostEstimates_asksAgainForNarrationAndEstimate_onlyForThatScene() {
        List<String> sceneIds = createStoryWithApprovedPrompts("rev-estimated");
        String revised = sceneIds.get(4);
        post("/stories/rev-estimated/narration", null, 200);
        post("/stories/rev-estimated/cost-estimates", null, 200);

        Map<String, Object> afterRevision = post("/stories/rev-estimated/prompt-decisions", List.of(Map.of(
                "sceneId", revised, "decision", "APPROVE",
                "revisedNarrationText", "Una onda expansiva así rompería vidrios en toda la ciudad.")), 200);

        assertThat((List<String>) afterRevision.get("succeededSceneIds")).containsExactly(revised);
        Map<String, Object> scene = sceneById(afterRevision, revised);
        assertThat(scene.get("narrationAudioPath")).isNull();
        assertThat(scene.get("costStatus")).isEqualTo("NOT_ESTIMATED");
        assertThat(scene.get("costEstimate")).isNull();
        assertThat(sceneById(afterRevision, sceneIds.get(0)).get("costStatus")).isEqualTo("ESTIMATED"); // las demás, intactas

        // vuelve a pedir narración y estimación: solo esa escena
        assertThat((List<String>) post("/stories/rev-estimated/narration", null, 200).get("succeededSceneIds"))
                .containsExactly(revised);
        Map<String, Object> reestimated = post("/stories/rev-estimated/cost-estimates", null, 200);
        assertThat((List<String>) reestimated.get("succeededSceneIds")).containsExactly(revised);
        assertThat(sceneById(reestimated, revised).get("costStatus")).isEqualTo("ESTIMATED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void puerta2RejectsApprovingAnInvalidatedCost_andGenerateSkipsTheScene() {
        List<String> sceneIds = createStoryWithApprovedPrompts("rev-approved");
        String revised = sceneIds.get(2);
        post("/stories/rev-approved/narration", null, 200);
        post("/stories/rev-approved/cost-estimates", null, 200);
        post("/stories/rev-approved/cost-decisions", approveAll(sceneIds), 200);

        post("/stories/rev-approved/prompt-decisions", List.of(Map.of(
                "sceneId", revised, "decision", "APPROVE",
                "revisedNarrationText", "Ocurrió el treinta de junio de 1908, en Siberia.")), 200);

        Map<String, Object> gate2 = post("/stories/rev-approved/cost-decisions",
                List.of(Map.of("sceneId", revised, "decision", "APPROVE")), 200);
        assertThat((List<String>) gate2.get("succeededSceneIds")).isEmpty();
        assertThat((Map<String, Object>) gate2.get("failedSceneIds")).containsKey(revised);
        assertThat(sceneById(gate2, revised).get("costStatus")).isEqualTo("NOT_ESTIMATED");

        Map<String, Object> generated = post("/stories/rev-approved/generate", null, 200);
        assertThat((List<String>) generated.get("succeededSceneIds"))
                .hasSize(sceneIds.size() - 1)
                .doesNotContain(revised);
    }

    @Test
    @SuppressWarnings("unchecked")
    void revisedNarrationAfterGenerationStarted_isRejected_andTheSceneKeepsItsState() {
        List<String> sceneIds = createStoryWithApprovedPrompts("rev-generated");
        String revised = sceneIds.get(1);
        post("/stories/rev-generated/narration", null, 200);
        post("/stories/rev-generated/cost-estimates", null, 200);
        post("/stories/rev-generated/cost-decisions", approveAll(sceneIds), 200);
        post("/stories/rev-generated/generate", null, 200);

        Map<String, Object> response = post("/stories/rev-generated/prompt-decisions", List.of(Map.of(
                "sceneId", revised, "decision", "APPROVE", "revisedNarrationText", "Otro texto.")), 200);

        assertThat((Map<String, String>) response.get("failedSceneIds")).containsKey(revised);
        assertThat(((Map<String, String>) response.get("failedSceneIds")).get(revised)).contains("reset-generation");
        Map<String, Object> scene = sceneById(response, revised);
        assertThat(scene.get("narrationText")).isNotEqualTo("Otro texto.");
        assertThat(scene.get("narrationAudioPath")).isNotNull();
        assertThat(scene.get("costStatus")).isEqualTo("APPROVED");
    }

    // --- helpers ----------------------------------------------------------------------------

    private List<String> createStoryWithApprovedPrompts(String storyId) {
        Map<String, Object> createBody = Map.of(
                "storyId", storyId,
                "topic", "Explosión de Tunguska (1908)",
                "title", "La explosión que derribó 80 millones de árboles",
                "coreFacts", List.of("El 30 de junio de 1908 un objeto espacial explotó en la atmósfera sobre Siberia."),
                "durationBudget", Map.of("minSeconds", 20, "maxSeconds", 60)
        );
        List<String> sceneIds = sceneIds(post("/stories", createBody, 201));
        Map<String, Object> tiersBody = new LinkedHashMap<>();
        for (String sceneId : sceneIds) {
            tiersBody.put(sceneId, Map.of(
                    "movementComplexity", 5, "elementCount", 5, "physicalRealism", 5, "cameraDynamism", 5));
        }
        post("/stories/" + storyId + "/tiers", tiersBody, 200);
        post("/stories/" + storyId + "/prompt-decisions", approveAll(sceneIds), 200);
        return sceneIds;
    }

    private static List<Map<String, Object>> approveAll(List<String> sceneIds) {
        return sceneIds.stream()
                .map(id -> Map.<String, Object>of("sceneId", id, "decision", "APPROVE"))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> sceneIds(Map<String, Object> storyMap) {
        List<Map<String, Object>> scenes = (List<Map<String, Object>>) storyMap.get("scenes");
        return scenes.stream().map(s -> (String) s.get("id")).toList();
    }

    /** Escena de una respuesta de batch ({story: {scenes}}). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> sceneById(Map<String, Object> response, String sceneId) {
        Map<String, Object> story = (Map<String, Object>) response.get("story");
        return ((List<Map<String, Object>>) story.get("scenes")).stream()
                .filter(s -> sceneId.equals(s.get("id")))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Object body, int expectedStatus) {
        ResponseEntity<Map> response = restTemplate.postForEntity("http://localhost:" + port + path, body, Map.class);
        assertThat(response.getStatusCode().value())
                .as("POST %s -> %s: %s", path, response.getStatusCode(), response.getBody())
                .isEqualTo(expectedStatus);
        return (Map<String, Object>) response.getBody();
    }

    @TestConfiguration
    static class FakeServicesConfig {

        @Bean
        @Primary
        HiggsfieldClient fakeHiggsfieldClient() {
            return new FakeHiggsfieldClient();
        }

        @Bean
        @Primary
        TtsService fakeTtsService() {
            return new FakeTtsService(WordsPerSecondDurationEstimator.neutralSpanish());
        }

        @Bean
        @Primary
        ScriptDraftingService fakeScriptDraftingService() {
            return new FakeScriptDraftingService();
        }
    }
}
