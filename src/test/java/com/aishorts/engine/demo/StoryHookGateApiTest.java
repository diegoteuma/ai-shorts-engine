package com.aishorts.engine.demo;

import com.aishorts.engine.clips.ClipFrameExtractor;
import com.aishorts.engine.clips.FakeClipDownloader;
import com.aishorts.engine.clips.Mp4Fixtures;
import com.aishorts.engine.duration.WordsPerSecondDurationEstimator;
import com.aishorts.engine.higgsfield.HiggsfieldClient;
import com.aishorts.engine.script.ScriptDraftingService;
import com.aishorts.engine.tts.TtsService;
import com.aishorts.engine.video.ProcessRunner;
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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compuerta del GANCHO por HTTP: POST /generate con { "sceneIds": [...] } y
 * POST /stories/{id}/scenes/{sceneId}/frames. FFmpeg es falso (un
 * ProcessRunner que escribe el JPG), así que no hace falta instalarlo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StoryHookGateApiTest.FakeServicesConfig.class)
class StoryHookGateApiTest {

    @TempDir
    static Path dataDir;

    /** Código de salida del FFmpeg falso para la próxima llamada (0 = bien). */
    static final AtomicInteger ffmpegExitCode = new AtomicInteger(0);

    @DynamicPropertySource
    static void engineProperties(DynamicPropertyRegistry registry) {
        registry.add("DATA_DIR", () -> dataDir.resolve("stories").toString());
        registry.add("AUDIO_DIR", () -> dataDir.resolve("audio").toString());
        registry.add("CLIPS_DIR", () -> dataDir.resolve("clips").toString());
        registry.add("FRAMES_DIR", () -> dataDir.resolve("frames").toString());
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
    void generateWithoutBody_generatesEveryApprovedScene() {
        List<String> sceneIds = createStoryWithApprovedCosts("gate-all");

        Map<String, Object> response = post("/stories/gate-all/generate", null, 200);

        assertThat(listOf(response.get("succeededSceneIds"))).containsExactlyElementsOf(sceneIds);
        assertThat(scenesOf(response)).allSatisfy(s -> assertThat(s.get("generationStatus")).isEqualTo("QUEUED"));
    }

    @Test
    void generateWithTheHookOnly_queuesOnlyTheHook_andAPollDoesNotDownloadItYet() {
        List<String> sceneIds = createStoryWithApprovedCosts("gate-hook");
        String hook = hookId(sceneIds);

        Map<String, Object> response = post("/stories/gate-hook/generate", Map.of("sceneIds", List.of(hook)), 200);

        assertThat(listOf(response.get("succeededSceneIds"))).containsExactly(hook);
        for (Map<String, Object> scene : scenesOf(response)) {
            assertThat(scene.get("generationStatus")).isEqualTo(hook.equals(scene.get("id")) ? "QUEUED" : "NOT_STARTED");
        }
        // poll: el GANCHO termina; ClipStore no baja nada hasta que estén TODAS COMPLETED (comportamiento sin cambios).
        Map<String, Object> polled = post("/stories/gate-hook/poll-generation", null, 200);
        assertThat(mapOf(polled.get("clips")).get("attempted")).isEqualTo(false);
        Map<String, Object> hookScene = scenesOf(polled).stream().filter(s -> hook.equals(s.get("id"))).findFirst().orElseThrow();
        assertThat(hookScene.get("generationStatus")).isEqualTo("COMPLETED");
        assertThat(hookScene.get("generatedAssetUrl")).isNotNull();
        // Y frames lo dice claro: sin clip local.
        Map<String, Object> frames = post("/stories/gate-hook/scenes/" + hook + "/frames", null, 409);
        assertThat(frames.get("error")).asString().contains("no tiene clip local").contains("generatedAssetUrl");

        // Después se genera el resto, sin repetir el GANCHO.
        Map<String, Object> rest = post("/stories/gate-hook/generate", null, 200);
        assertThat(listOf(rest.get("succeededSceneIds"))).hasSize(sceneIds.size() - 1).doesNotContain(hook);
    }

    @Test
    void generateWithAnUnknownScene_reportsItInFailed_andAnEmptyListOrUnknownFieldIs400() {
        List<String> sceneIds = createStoryWithApprovedCosts("gate-bad");
        String hook = hookId(sceneIds);

        Map<String, Object> response = post("/stories/gate-bad/generate", Map.of("sceneIds", List.of("no-existe", hook)), 200);
        assertThat(listOf(response.get("succeededSceneIds"))).containsExactly(hook);
        assertThat(mapOf(response.get("failedSceneIds"))).containsOnlyKeys("no-existe");

        assertThat(post("/stories/gate-bad/generate", Map.of("sceneIds", List.of()), 400).get("error"))
                .asString().contains("sceneIds no puede estar vacío");
        assertThat(post("/stories/gate-bad/generate", Map.of("scenes", List.of(hook)), 400).get("error"))
                .asString().contains("Campo desconocido 'scenes'");
        assertThat(post("/stories/gate-bad/generate", Map.of("sceneIds", "x"), 400).get("error"))
                .asString().contains("debe ser un array");
    }

    @Test
    void frames_extractsThreeJpgsFromTheClipStartOffset_clampedToTheClipDuration() throws IOException {
        List<String> sceneIds = createStoryWithApprovedCosts("gate-frames");
        String hook = hookId(sceneIds);
        post("/stories/gate-frames/generate", null, 200);
        post("/stories/gate-frames/poll-generation", null, 200);
        // El clip local del GANCHO pasa a durar 4 s (el del fake no declara duración): con offset 3 s, +1 y +2 se acotan.
        Files.write(dataDir.resolve("clips").resolve(hook + ".mp4"),
                Mp4Fixtures.of(720, 1280).mediaDuration(0, 1000, 4000).build());

        Map<String, Object> response = post("/stories/gate-frames/scenes/" + hook + "/frames", null, 200);

        assertThat(number(response.get("clipStartOffset"))).isEqualTo(3.0);
        assertThat(number(response.get("clipDurationSeconds"))).isEqualTo(4.0);
        List<Map<String, Object>> frames = listOfMaps(response.get("frames"));
        assertThat(frames).extracting(f -> number(f.get("requestedSeconds"))).containsExactly(3.0, 4.0, 5.0);
        assertThat(frames).extracting(f -> number(f.get("seconds"))).containsExactly(3.0, 3.95, 3.95);
        assertThat(frames).extracting(f -> f.get("clamped")).containsExactly(false, true, true);
        for (Map<String, Object> frame : frames) {
            Path path = Path.of((String) frame.get("path"));
            assertThat(Files.isRegularFile(path)).isTrue();
            assertThat(path.startsWith(dataDir.resolve("frames").toAbsolutePath().normalize())).isTrue();
        }
    }

    @Test
    void frames_failsClearly_onUnknownScene_unreadableDuration_missingFile_andFfmpegError() throws IOException {
        List<String> sceneIds = createStoryWithApprovedCosts("gate-frames-bad");
        String hook = hookId(sceneIds);
        post("/stories/gate-frames-bad/generate", null, 200);
        post("/stories/gate-frames-bad/poll-generation", null, 200);
        Path clip = dataDir.resolve("clips").resolve(hook + ".mp4");

        post("/stories/gate-frames-bad/scenes/no-existe/frames", null, 404);
        post("/stories/no-existe/scenes/" + hook + "/frames", null, 404);
        // El MP4 del fake no tiene caja mdhd: no se sabe cuánto dura.
        assertThat(post("/stories/gate-frames-bad/scenes/" + hook + "/frames", null, 422).get("error"))
                .asString().contains("no declara su duración");

        Files.write(clip, Mp4Fixtures.of(720, 1280).mediaDuration(0, 1000, 8000).build());
        ffmpegExitCode.set(1);
        try {
            assertThat(post("/stories/gate-frames-bad/scenes/" + hook + "/frames", null, 422).get("error"))
                    .asString().contains("FFmpeg falló (código 1)");
        } finally {
            ffmpegExitCode.set(0);
        }

        Files.delete(clip);
        assertThat(post("/stories/gate-frames-bad/scenes/" + hook + "/frames", null, 409).get("error"))
                .asString().contains("el archivo no existe");
    }

    // --- helpers ----------------------------------------------------------------------------

    /** Lleva una historia de 6 escenas hasta la PUERTA 2 aprobada, sin generar. */
    private List<String> createStoryWithApprovedCosts(String storyId) {
        Map<String, Object> created = post("/stories", Map.of(
                "storyId", storyId,
                "topic", "Explosión de Tunguska (1908)",
                "title", "La explosión que derribó 80 millones de árboles",
                "coreFacts", List.of("El 30 de junio de 1908 un objeto espacial explotó en la atmósfera sobre Siberia."),
                "durationBudget", Map.of("minSeconds", 20, "maxSeconds", 60)), 201);
        List<String> sceneIds = scenesOf(created).stream().map(s -> (String) s.get("id")).toList();
        Map<String, Object> tiers = new LinkedHashMap<>();
        for (String id : sceneIds) {
            tiers.put(id, Map.of("movementComplexity", 5, "elementCount", 5, "physicalRealism", 5, "cameraDynamism", 5));
        }
        post("/stories/" + storyId + "/tiers", tiers, 200);
        post("/stories/" + storyId + "/prompt-decisions", approveAll(sceneIds), 200);
        post("/stories/" + storyId + "/narration", null, 200);
        post("/stories/" + storyId + "/cost-estimates", null, 200);
        post("/stories/" + storyId + "/cost-decisions", approveAll(sceneIds), 200);
        return sceneIds;
    }

    private static String hookId(List<String> sceneIds) {
        return sceneIds.get(0); // order 1 = GANCHO
    }

    private static List<Map<String, Object>> approveAll(List<String> sceneIds) {
        return sceneIds.stream().map(id -> Map.<String, Object>of("sceneId", id, "decision", "APPROVE")).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> scenesOf(Map<String, Object> response) {
        Map<String, Object> story = response.containsKey("story") ? (Map<String, Object>) response.get("story") : response;
        return (List<Map<String, Object>>) story.get("scenes");
    }

    /** El cliente del test lee los decimales como BigDecimal. */
    private static double number(Object value) {
        return ((Number) value).doubleValue();
    }

    @SuppressWarnings("unchecked")
    private static List<String> listOf(Object value) {
        return (List<String>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return (Map<String, Object>) value;
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

        @Bean
        @Primary
        FakeClipDownloader fakeClipDownloader() {
            return new FakeClipDownloader();
        }

        /** FFmpeg falso: escribe un JPG mínimo en el último argumento, o sale con ffmpegExitCode. */
        @Bean
        @Primary
        ClipFrameExtractor fakeFrameExtractor() {
            ProcessRunner fakeFfmpeg = (command, workingDir, env, timeout, log) -> {
                int exit = ffmpegExitCode.get();
                if (exit == 0) {
                    try {
                        Files.write(Path.of(command.get(command.size() - 1)), new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
                return new ProcessRunner.Result(exit, false);
            };
            return new ClipFrameExtractor(dataDir.resolve("frames"), List.of("ffmpeg"), fakeFfmpeg, Duration.ofSeconds(5));
        }
    }
}
