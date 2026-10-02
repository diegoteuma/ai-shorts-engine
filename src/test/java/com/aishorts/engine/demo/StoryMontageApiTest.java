package com.aishorts.engine.demo;

import com.aishorts.engine.captions.CaptionTranslationService;
import com.aishorts.engine.duration.WordsPerSecondDurationEstimator;
import com.aishorts.engine.higgsfield.HiggsfieldClient;
import com.aishorts.engine.higgsfield.KnownHiggsfieldPricing;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.aishorts.engine.montage.SyntheticMedia.generateColorClip;
import static com.aishorts.engine.montage.SyntheticMedia.generateSineAudio;
import static com.aishorts.engine.montage.SyntheticMedia.probeResolution;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * POST /stories/{id}/montage de punta a punta vía HTTP real, mismo andamiaje
 * (Fake* por @Primary) que StoryResetGenerationApiTest. Los clips se leen de
 * CLIPS_DIR y el audio del narrationAudioPath que guardó /narration; ffmpeg
 * es real, sobre medios sintéticos (SyntheticMedia).
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(StoryMontageApiTest.FakeServicesConfig.class)
class StoryMontageApiTest {

    private static final String[] COLORS = {"red", "orange", "yellow", "green", "blue", "purple"};

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void engineProperties(DynamicPropertyRegistry registry) {
        registry.add("DATA_DIR", () -> dataDir.resolve("stories").toString());
        registry.add("AUDIO_DIR", () -> dataDir.resolve("audio").toString());
        registry.add("CLIPS_DIR", () -> dataDir.resolve("clips").toString());
        registry.add("OUTPUT_DIR", () -> dataDir.resolve("output").toString());
        registry.add("CLAUDE_API_KEY", () -> "test-claude-key");
        registry.add("CLAUDE_MODEL", () -> "test-claude-model");
        registry.add("HIGGSFIELD_BASE_URL", () -> "https://higgsfield.invalid");
        registry.add("HIGGSFIELD_API_KEY_ID", () -> "test-id");
        registry.add("HIGGSFIELD_API_KEY_SECRET", () -> "test-secret");
        registry.add("HIGGSFIELD_MODEL_STANDARD", () -> KnownHiggsfieldPricing.STANDARD_MODEL_ID);
        registry.add("HIGGSFIELD_MODEL_PREMIUM", () -> KnownHiggsfieldPricing.PREMIUM_MODEL_ID);
        registry.add("ELEVENLABS_API_KEY", () -> "test-elevenlabs-key");
        registry.add("ELEVENLABS_VOICE_ID", () -> "test-voice");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @SuppressWarnings("unchecked")
    void withoutClips_returns400ListingEveryMissingClipPath() {
        List<String> sceneIds = createFullyGeneratedStory("sin-clips");

        Map<String, Object> response = post("/stories/sin-clips/montage", null, 400);

        Path clipsDir = dataDir.resolve("clips").toAbsolutePath();
        List<Map<String, Object>> missing = (List<Map<String, Object>>) response.get("missing");
        // Los audios sí están (los guardó /narration): solo faltan los 6 clips, en orden.
        assertThat(missing).extracting(m -> m.get("kind")).containsOnly("clip");
        assertThat(missing).extracting(m -> m.get("expectedPath")).containsExactlyElementsOf(
                sceneIds.stream().map(id -> clipsDir.resolve(id + ".mp4").toString()).toList());
        for (String sceneId : sceneIds) {
            assertThat(response.get("error")).asString()
                    .contains(sceneId + ": falta el clip de video '" + sceneId + ".mp4', se espera en "
                            + clipsDir.resolve(sceneId + ".mp4"));
        }
        assertThat(dataDir.resolve("output").resolve("sin-clips")).doesNotExist();
    }

    @Test
    @SuppressWarnings("unchecked")
    void withMissingAudio_returns400NamingTheAudioPath() throws Exception {
        List<String> sceneIds = createFullyGeneratedStory("sin-audio");
        Files.createDirectories(dataDir.resolve("clips"));
        for (int i = 0; i < sceneIds.size(); i++) {
            generateColorClip(dataDir.resolve("clips").resolve(sceneIds.get(i) + ".mp4"), COLORS[i]);
        }
        Path deletedAudio = narrationAudioPaths("sin-audio").get(2);
        Files.delete(deletedAudio);

        Map<String, Object> response = post("/stories/sin-audio/montage", null, 400);

        List<Map<String, Object>> missing = (List<Map<String, Object>>) response.get("missing");
        assertThat(missing).hasSize(1);
        assertThat(missing.get(0).get("sceneId")).isEqualTo(sceneIds.get(2));
        assertThat(missing.get(0).get("kind")).isEqualTo("audio");
        assertThat(missing.get(0).get("expectedPath")).isEqualTo(deletedAudio.toAbsolutePath().toString());
    }

    @Test
    void withAllFiles_returnsTheFinalVideoPath_1080x1920() throws Exception {
        List<String> sceneIds = createFullyGeneratedStory("1");
        Files.createDirectories(dataDir.resolve("clips"));
        for (int i = 0; i < sceneIds.size(); i++) {
            generateColorClip(dataDir.resolve("clips").resolve(sceneIds.get(i) + ".mp4"), COLORS[i]);
        }
        // Los Fake* de TTS escriben bytes de relleno; se reemplazan por mp3 reales en la misma ruta.
        for (Path audio : narrationAudioPaths("1")) {
            generateSineAudio(audio);
        }

        Map<String, Object> response = post("/stories/1/montage", null, 200);

        Path storyOutputDir = dataDir.resolve("output").resolve("1").toAbsolutePath();
        Path video = Path.of((String) response.get("videoPath"));
        assertThat(video).isEqualTo(storyOutputDir.resolve("1.mp4"));
        assertThat(video).isRegularFile();
        assertThat(probeResolution(video)).containsExactly(1080, 1920);
        assertThat(Path.of((String) response.get("srtEsPath"))).isEqualTo(storyOutputDir.resolve("1.es.srt")).isRegularFile();
        assertThat(Path.of((String) response.get("srtEnPath"))).isEqualTo(storyOutputDir.resolve("1.en.srt")).isRegularFile();
        assertThat(response.get("story")).isNotNull();
    }

    @Test
    void onUnknownStory_returns404() {
        post("/stories/no-existe/montage", null, 404);
    }

    // --- helpers ----------------------------------------------------------------------------

    /** Lleva una historia de 6 escenas hasta COMPLETED, con audio guardado por /narration (Fake TTS). */
    private List<String> createFullyGeneratedStory(String storyId) {
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
        List<Map<String, Object>> approveAll = sceneIds.stream()
                .map(id -> Map.<String, Object>of("sceneId", id, "decision", "APPROVE"))
                .toList();
        post("/stories/" + storyId + "/tiers", tiersBody, 200);
        post("/stories/" + storyId + "/prompt-decisions", approveAll, 200);
        post("/stories/" + storyId + "/narration", null, 200);
        post("/stories/" + storyId + "/cost-estimates", null, 200);
        post("/stories/" + storyId + "/cost-decisions", approveAll, 200);
        post("/stories/" + storyId + "/generate", null, 200);
        post("/stories/" + storyId + "/poll-generation", null, 200);
        return sceneIds;
    }

    @SuppressWarnings("unchecked")
    private List<Path> narrationAudioPaths(String storyId) {
        Map<String, Object> story = restTemplate.getForObject(baseUrl("/stories/" + storyId), Map.class);
        List<Map<String, Object>> scenes = (List<Map<String, Object>>) story.get("scenes");
        return scenes.stream().map(s -> Path.of((String) s.get("narrationAudioPath"))).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> sceneIds(Map<String, Object> storyMap) {
        List<Map<String, Object>> scenes = (List<Map<String, Object>>) storyMap.get("scenes");
        return scenes.stream().map(s -> (String) s.get("id")).toList();
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
