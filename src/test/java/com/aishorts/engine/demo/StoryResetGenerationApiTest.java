package com.aishorts.engine.demo;

import com.aishorts.engine.captions.CaptionTranslationService;
import com.aishorts.engine.domain.GenerationStatus;
import com.aishorts.engine.domain.SceneCostStatus;
import com.aishorts.engine.domain.Story;
import com.aishorts.engine.duration.WordsPerSecondDurationEstimator;
import com.aishorts.engine.higgsfield.HiggsfieldClient;
import com.aishorts.engine.persistence.JsonFileStoryRepository;
import com.aishorts.engine.persistence.StoryRepository;
import com.aishorts.engine.script.ScriptDraftingService;
import com.aishorts.engine.tts.TtsService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cubre POST /stories/{id}/reset-generation de punta a punta vía HTTP real,
 * mismo andamiaje (Fake* por @Primary) que StoryApiPersistenceRestartTest —
 * clase aparte porque el golden path ya es un único test largo allá, y acá
 * hay varios escenarios chicos independientes sobre un reset.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StoryResetGenerationApiTest.FakeServicesConfig.class)
class StoryResetGenerationApiTest {

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

    @Autowired
    private StoryRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void resetWithoutBody_resetsAllScenes() {
        List<String> sceneIds = createFullyGeneratedStory("story-reset-all");

        Map<String, Object> response = postReset("story-reset-all", null, 200);

        assertThat(listOf(response.get("succeededSceneIds"))).containsExactlyInAnyOrderElementsOf(sceneIds);
        assertThat(mapOf(response.get("failedSceneIds"))).isEmpty();
        assertScenesReset(scenesOf(response), sceneIds);
    }

    @Test
    void resetWithPartialList_resetsOnlyThoseScenes_restStayIntact() {
        List<String> sceneIds = createFullyGeneratedStory("story-reset-partial");
        List<String> toReset = sceneIds.subList(0, 2);
        List<String> untouched = sceneIds.subList(2, sceneIds.size());

        Map<String, Object> response = postReset("story-reset-partial", toReset, 200);

        assertThat(listOf(response.get("succeededSceneIds"))).containsExactlyInAnyOrderElementsOf(toReset);
        assertThat(mapOf(response.get("failedSceneIds"))).isEmpty();

        List<Map<String, Object>> scenes = scenesOf(response);
        assertScenesReset(scenes, toReset);
        for (Map<String, Object> scene : scenes) {
            if (untouched.contains(scene.get("id"))) {
                assertThat(scene.get("generationStatus")).isEqualTo("COMPLETED");
                assertThat(scene.get("costStatus")).isEqualTo("APPROVED");
            }
        }
    }

    @Test
    void resetWithUnknownIdMixedWithValidOne_returns200WithUnknownInFailed() {
        List<String> sceneIds = createFullyGeneratedStory("story-reset-mixed");
        String validId = sceneIds.get(0);

        Map<String, Object> response = postReset("story-reset-mixed", List.of(validId, "does-not-exist"), 200);

        assertThat(listOf(response.get("succeededSceneIds"))).containsExactly(validId);
        Map<String, Object> failed = mapOf(response.get("failedSceneIds"));
        assertThat(failed.keySet()).containsExactly("does-not-exist");
    }

    @Test
    void resetWithEmptyList_returns400() {
        createFullyGeneratedStory("story-reset-empty");

        Map<String, Object> response = postReset("story-reset-empty", List.of(), 400);

        assertThat(response.get("error")).asString().contains("sceneIds no puede estar vacío");
    }

    @Test
    void resetOnUnknownStory_returns404() {
        postReset("does-not-exist", null, 404);
    }

    @Test
    void resetPersistsAcrossASimulatedProcessRestart() {
        List<String> sceneIds = createFullyGeneratedStory("story-reset-persist");

        postReset("story-reset-persist", null, 200);

        StoryRepository reopened = new JsonFileStoryRepository(dataDir.resolve("stories"), objectMapper);
        Story reloaded = reopened.findById("story-reset-persist").orElseThrow();
        assertThat(reloaded.scenes()).hasSize(sceneIds.size());
        assertThat(reloaded.scenes()).allSatisfy(scene -> {
            assertThat(scene.costStatus()).isEqualTo(SceneCostStatus.NOT_ESTIMATED);
            assertThat(scene.costEstimate()).isNull();
            assertThat(scene.generationStatus()).isEqualTo(GenerationStatus.NOT_STARTED);
            assertThat(scene.generatedAssetUrl()).isNull();
            // la narración real sobrevive al reset y al reinicio simulado.
            assertThat(scene.hasNarrationAudio()).isTrue();
        });
    }

    // --- helpers ----------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void assertScenesReset(List<Map<String, Object>> scenes, List<String> resetSceneIds) {
        for (Map<String, Object> scene : scenes) {
            if (!resetSceneIds.contains(scene.get("id"))) {
                continue;
            }
            assertThat(scene.get("costStatus")).isEqualTo("NOT_ESTIMATED");
            assertThat(scene.get("costEstimate")).isNull();
            assertThat(scene.get("generationStatus")).isEqualTo("NOT_STARTED");
            assertThat(scene.get("generatedAssetUrl")).isNull();
            assertThat(scene.get("higgsfieldRequestId")).isNull();
            // la narración real no se toca.
            assertThat(scene.get("narrationAudioPath")).isNotNull();
            assertThat(scene.get("promptStatus")).isEqualTo("APPROVED");
        }
    }

    /** Lleva una historia de 6 escenas de punta a punta hasta COMPLETED (mismo camino que el golden path). */
    private List<String> createFullyGeneratedStory(String storyId) {
        Map<String, Object> createBody = Map.of(
                "storyId", storyId,
                "topic", "Explosión de Tunguska (1908)",
                "title", "La explosión que derribó 80 millones de árboles",
                "coreFacts", List.of("El 30 de junio de 1908 un objeto espacial explotó en la atmósfera sobre Siberia."),
                "durationBudget", Map.of("minSeconds", 20, "maxSeconds", 60)
        );
        Map<String, Object> created = post("/stories", createBody, 201);
        List<String> sceneIds = sceneIds(created);

        Map<String, Object> tiersBody = new java.util.LinkedHashMap<>();
        for (String sceneId : sceneIds) {
            tiersBody.put(sceneId, Map.of(
                    "movementComplexity", 5, "elementCount", 5, "physicalRealism", 5, "cameraDynamism", 5));
        }
        post("/stories/" + storyId + "/tiers", tiersBody, 200);
        post("/stories/" + storyId + "/prompt-decisions", approveAll(sceneIds), 200);
        post("/stories/" + storyId + "/narration", null, 200);
        post("/stories/" + storyId + "/cost-estimates", null, 200);
        post("/stories/" + storyId + "/cost-decisions", approveAll(sceneIds), 200);
        post("/stories/" + storyId + "/generate", null, 200);
        post("/stories/" + storyId + "/poll-generation", null, 200);

        return sceneIds;
    }

    private List<Map<String, Object>> approveAll(List<String> sceneIds) {
        return sceneIds.stream()
                .map(id -> Map.<String, Object>of("sceneId", id, "decision", "APPROVE"))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> sceneIds(Map<String, Object> storyMap) {
        List<Map<String, Object>> scenes = (List<Map<String, Object>>) storyMap.get("scenes");
        return scenes.stream().map(s -> (String) s.get("id")).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> scenesOf(Map<String, Object> response) {
        Map<String, Object> story = (Map<String, Object>) response.get("story");
        return (List<Map<String, Object>>) story.get("scenes");
    }

    @SuppressWarnings("unchecked")
    private List<String> listOf(Object value) {
        return (List<String>) value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapOf(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Object body, int expectedStatus) {
        ResponseEntity<Map> response = restTemplate.postForEntity(baseUrl(path), body, Map.class);
        assertThat(response.getStatusCode().value())
                .as("POST %s -> %s: %s", path, response.getStatusCode(), response.getBody())
                .isEqualTo(expectedStatus);
        return (Map<String, Object>) response.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postReset(String storyId, List<String> sceneIds, int expectedStatus) {
        String path = "/stories/" + storyId + "/reset-generation";
        if (sceneIds != null) {
            return post(path, sceneIds, expectedStatus);
        }
        // Body genuinamente omitido -- postForEntity(url, null, ...) y HttpEntity.EMPTY no sirven
        // acá: TestRestTemplate arma por default Content-Type: application/x-www-form-urlencoded
        // para un body nulo/vacío, y Spring no tiene un HttpMessageConverter que lea List<String>
        // desde ese tipo -> 415 antes de llegar al "required = false". Con Content-Type: application/json
        // explícito y 0 bytes de body, Jackson sí matchea y Spring entra al camino de body vacío,
        // que respeta required=false y resuelve sceneIds a null.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = restTemplate.exchange(
                baseUrl(path), HttpMethod.POST, new HttpEntity<>(headers), Map.class);
        assertThat(response.getStatusCode().value())
                .as("POST %s (sin body) -> %s: %s", path, response.getStatusCode(), response.getBody())
                .isEqualTo(expectedStatus);
        return (Map<String, Object>) response.getBody();
    }

    private String baseUrl(String path) {
        return "http://localhost:" + port + path;
    }

    // --- reemplazo de los servicios pagos por los Fake* del paquete demo --------------------

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

        @Bean
        @Primary
        CaptionTranslationService fakeCaptionTranslationService() {
            return new FakeCaptionTranslationService();
        }
    }
}
