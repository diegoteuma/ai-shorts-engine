package com.aishorts.engine.demo;

import com.aishorts.engine.clips.FakeClipDownloader;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guardado local de los clips vía HTTP real: poll-generation (cuando TODAS las
 * escenas quedan COMPLETED), su fallo parcial reintentable y el borrado que
 * hace reset-generation. Usa FakeClipDownloader, así que no toca la red.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StoryClipStorageApiTest.FakeServicesConfig.class)
class StoryClipStorageApiTest {

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

    @Autowired
    private FakeClipDownloader clipDownloader;

    @Test
    @SuppressWarnings("unchecked")
    void pollingStoresEveryClipOnceAllScenesAreCompleted() throws IOException {
        List<String> sceneIds = createGeneratedStoryWithoutPolling("story-clips-ok");

        Map<String, Object> polled = post("/stories/story-clips-ok/poll-generation", null, 200);

        Map<String, Object> clips = (Map<String, Object>) polled.get("clips");
        assertThat(clips.get("attempted")).isEqualTo(true);
        assertThat((List<String>) clips.get("storedSceneIds")).containsExactlyInAnyOrderElementsOf(sceneIds);
        assertThat((Map<String, Object>) clips.get("failedSceneIds")).isEmpty();
        for (Map<String, Object> scene : scenesOf(polled)) {
            String id = (String) scene.get("id");
            assertThat(scene.get("localClipPath")).isEqualTo(id + ".mp4");
            assertThat(Files.isRegularFile(dataDir.resolve("clips").resolve(id + ".mp4"))).isTrue();
        }
        // lo persistido coincide con la respuesta (GET lee del repositorio).
        assertThat(scenesOf(get("/stories/story-clips-ok")).stream().allMatch(s -> s.get("localClipPath") != null)).isTrue();
        // y no queda ningún temporal suelto.
        assertThat(leftoverPartFiles()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFailedDownloadDoesNotBlockPollingAndTheNextPollRetriesOnlyTheMissingClip() throws IOException {
        List<String> sceneIds = createGeneratedStoryWithoutPolling("story-clips-retry");
        clipDownloader.failNextCalls(1);

        Map<String, Object> first = post("/stories/story-clips-retry/poll-generation", null, 200);

        Map<String, Object> firstClips = (Map<String, Object>) first.get("clips");
        Map<String, Object> failed = (Map<String, Object>) firstClips.get("failedSceneIds");
        assertThat(failed).hasSize(1);
        String missingId = failed.keySet().iterator().next();
        for (Map<String, Object> scene : scenesOf(first)) {
            // la generación sigue COMPLETED con su URL; solo falta (o sobra) la copia local.
            assertThat(scene.get("generationStatus")).isEqualTo("COMPLETED");
            assertThat(scene.get("generatedAssetUrl")).isNotNull();
            if (missingId.equals(scene.get("id"))) {
                assertThat(scene.get("localClipPath")).isNull();
                assertThat(scene.get("localClipFailureReason")).isNotNull();
            } else {
                assertThat(scene.get("localClipPath")).isNotNull();
            }
        }

        Map<String, Object> second = post("/stories/story-clips-retry/poll-generation", null, 200);

        Map<String, Object> secondClips = (Map<String, Object>) second.get("clips");
        assertThat((List<String>) secondClips.get("storedSceneIds")).containsExactly(missingId);
        assertThat((Map<String, Object>) secondClips.get("failedSceneIds")).isEmpty();
        assertThat((List<String>) secondClips.get("alreadyPresentSceneIds")).hasSize(sceneIds.size() - 1);
        for (Map<String, Object> scene : scenesOf(second)) {
            assertThat(scene.get("localClipPath")).isNotNull();
            assertThat(scene.get("localClipFailureReason")).isNull();
        }
    }

    @Test
    void resetGenerationDeletesTheLocalClipOfTheResetScenesOnly() throws IOException {
        List<String> sceneIds = createGeneratedStoryWithoutPolling("story-clips-reset");
        post("/stories/story-clips-reset/poll-generation", null, 200);
        List<String> toReset = sceneIds.subList(0, 2);
        List<String> untouched = sceneIds.subList(2, sceneIds.size());

        Map<String, Object> reset = post("/stories/story-clips-reset/reset-generation", toReset, 200);

        for (String id : toReset) {
            assertThat(Files.exists(dataDir.resolve("clips").resolve(id + ".mp4"))).isFalse();
        }
        for (String id : untouched) {
            assertThat(Files.isRegularFile(dataDir.resolve("clips").resolve(id + ".mp4"))).isTrue();
        }
        for (Map<String, Object> scene : scenesOf(reset)) {
            if (toReset.contains(scene.get("id"))) {
                assertThat(scene.get("localClipPath")).isNull();
                assertThat(scene.get("generatedAssetUrl")).isNull();
            } else {
                assertThat(scene.get("localClipPath")).isNotNull();
            }
        }
    }

    // --- helpers ----------------------------------------------------------------------------

    /** Lleva una historia de 6 escenas hasta /generate (escenas en cola), SIN hacer poll. */
    private List<String> createGeneratedStoryWithoutPolling(String storyId) {
        Map<String, Object> createBody = Map.of(
                "storyId", storyId,
                "topic", "Explosión de Tunguska (1908)",
                "title", "La explosión que derribó 80 millones de árboles",
                "coreFacts", List.of("El 30 de junio de 1908 un objeto espacial explotó en la atmósfera sobre Siberia."),
                "durationBudget", Map.of("minSeconds", 20, "maxSeconds", 60)
        );
        Map<String, Object> created = post("/stories", createBody, 201);
        List<String> sceneIds = sceneIds(created);

        Map<String, Object> tiersBody = new LinkedHashMap<>();
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

    /** Escenas de una respuesta de batch ({story: {scenes}}) o de GET /stories/{id} ({scenes}). */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> scenesOf(Map<String, Object> response) {
        Map<String, Object> story = response.containsKey("story") ? (Map<String, Object>) response.get("story") : response;
        return (List<Map<String, Object>>) story.get("scenes");
    }

    private List<String> leftoverPartFiles() throws IOException {
        try (Stream<Path> files = Files.list(dataDir.resolve("clips"))) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".part"))
                    .toList();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path) {
        ResponseEntity<Map> response = restTemplate.getForEntity(baseUrl(path), Map.class);
        assertThat(response.getStatusCode().value()).as("GET %s -> %s", path, response.getStatusCode()).isEqualTo(200);
        return (Map<String, Object>) response.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Object body, int expectedStatus) {
        ResponseEntity<Map> response = restTemplate.postForEntity(baseUrl(path), body, Map.class);
        assertThat(response.getStatusCode().value())
                .as("POST %s -> %s: %s", path, response.getStatusCode(), response.getBody())
                .isEqualTo(expectedStatus);
        return (Map<String, Object>) response.getBody();
    }

    private String baseUrl(String path) {
        return "http://localhost:" + port + path;
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

        @Bean
        @Primary
        FakeClipDownloader fakeClipDownloader() {
            return new FakeClipDownloader();
        }
    }
}
