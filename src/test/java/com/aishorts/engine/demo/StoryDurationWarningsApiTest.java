package com.aishorts.engine.demo;

import com.aishorts.engine.higgsfield.HiggsfieldClient;
import com.aishorts.engine.script.SceneDraft;
import com.aishorts.engine.script.ScriptDraftingService;
import com.aishorts.engine.tts.TtsResult;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Campo durationWarnings de POST /stories/{id}/narration y /cost-estimates
 * vía HTTP real. El TTS falso devuelve las duraciones reales del audio de
 * carrington-1859 (por escena, en orden); Higgsfield es FakeHiggsfieldClient.
 * Solo avisa: los pasos siguen respondiendo 200 y se puede seguir.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StoryDurationWarningsApiTest.FakeServicesConfig.class)
class StoryDurationWarningsApiTest {

    /** targetDuration real de carrington-1859, de GANCHO a CIERRE. */
    private static final long[] CARRINGTON_MILLIS = {5_016, 7_988, 8_731, 5_526, 9_799, 4_644};

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
    void narration_returnsTheRealDurationWarnings() {
        List<String> sceneIds = createStoryWithApprovedPrompts("dur-narration");

        Map<String, Object> response = post("/stories/dur-narration/narration", null, 200);

        assertThat(response).containsKeys("succeededSceneIds", "failedSceneIds", "story"); // el formato de antes sigue igual
        assertCarringtonWarnings(response, sceneIds);
    }

    @Test
    void costEstimates_returnsTheSameWarnings_andDoNotBlockTheNextStep() {
        List<String> sceneIds = createStoryWithApprovedPrompts("dur-costs");
        post("/stories/dur-costs/narration", null, 200);

        Map<String, Object> response = post("/stories/dur-costs/cost-estimates", null, 200);

        assertThat((List<?>) response.get("succeededSceneIds")).hasSize(sceneIds.size());
        assertCarringtonWarnings(response, sceneIds);
    }

    @Test
    void costEstimatesWithoutNarration_doesNotWarnAboutTheWordCountEstimates() {
        // FakeScriptDraftingService: el GANCHO estimado por palabras dura ~2.3 s (< 3), pero no es duración real.
        createStoryWithApprovedPrompts("dur-no-audio");

        Map<String, Object> response = post("/stories/dur-no-audio/cost-estimates", null, 200);

        assertThat((List<?>) response.get("durationWarnings")).isEmpty();
    }

    // --- helpers ----------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void assertCarringtonWarnings(Map<String, Object> response, List<String> sceneIds) {
        // El ObjectMapper de la app lee los decimales como BigDecimal (USE_BIG_DECIMAL_FOR_FLOATS): se comparan como double.
        List<Map<String, Object>> warnings = ((List<Map<String, Object>>) response.get("durationWarnings")).stream()
                .map(w -> warning((String) w.get("sceneId"), ((Number) w.get("actualSeconds")).doubleValue(),
                        ((Number) w.get("limitSeconds")).doubleValue(), (String) w.get("kind")))
                .toList();
        assertThat(warnings).containsExactly(
                warning(sceneIds.get(2), 8.731, 8.0, "SCENE_TOO_LONG"),
                warning(sceneIds.get(4), 9.799, 8.0, "SCENE_TOO_LONG"),
                warning(null, 41.704, 40.0, "TOTAL_TOO_LONG"));
    }

    private static Map<String, Object> warning(String sceneId, double actual, double limit, String kind) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sceneId", sceneId);
        map.put("actualSeconds", actual);
        map.put("limitSeconds", limit);
        map.put("kind", kind);
        return map;
    }

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
        post("/stories/" + storyId + "/prompt-decisions", sceneIds.stream()
                .map(id -> Map.<String, Object>of("sceneId", id, "decision", "APPROVE"))
                .toList(), 200);
        return sceneIds;
    }

    @SuppressWarnings("unchecked")
    private static List<String> sceneIds(Map<String, Object> storyMap) {
        List<Map<String, Object>> scenes = (List<Map<String, Object>>) storyMap.get("scenes");
        return scenes.stream().map(s -> (String) s.get("id")).toList();
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
        ScriptDraftingService fakeScriptDraftingService() {
            return new FakeScriptDraftingService();
        }

        /** Cada narración de FakeScriptDraftingService dura lo que duró la escena del mismo rol en carrington-1859. */
        @Bean
        @Primary
        TtsService carringtonDurationsTts() {
            List<SceneDraft> scenes = new FakeScriptDraftingService().draftScenes(null);
            Map<String, Duration> byText = new HashMap<>();
            for (int i = 0; i < scenes.size(); i++) {
                byText.put(scenes.get(i).narrationText(), Duration.ofMillis(CARRINGTON_MILLIS[i]));
            }
            return text -> new TtsResult(("FAKE_MP3_AUDIO:" + text).getBytes(StandardCharsets.UTF_8), byText.get(text));
        }
    }
}
