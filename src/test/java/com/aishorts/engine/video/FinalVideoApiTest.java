package com.aishorts.engine.video;

import com.aishorts.engine.clips.Mp4Fixtures;
import com.aishorts.engine.domain.Story;
import com.aishorts.engine.persistence.SnapshotMapper;
import com.aishorts.engine.persistence.StoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POST/GET /stories/{id}/final-video por HTTP real, con FakeProcessRunner en
 * lugar de Node. Carpetas temporales para historias, clips, audio y renders
 * (no toca ./data).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FinalVideoApiTest.FakeRunnerConfig.class)
class FinalVideoApiTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void engineProperties(DynamicPropertyRegistry registry) {
        registry.add("DATA_DIR", () -> dataDir.resolve("stories").toString());
        registry.add("AUDIO_DIR", () -> dataDir.resolve("audio").toString());
        registry.add("CLIPS_DIR", () -> dataDir.resolve("clips").toString());
        registry.add("RENDERS_DIR", () -> dataDir.resolve("renders").toString());
        registry.add("FINAL_VIDEO_ENABLED", () -> "true");
        registry.add("FINAL_VIDEO_MIN_FREE_BYTES", () -> "1");
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
    private TestRestTemplate rest;

    @Autowired
    private FakeProcessRunner runner;

    @Autowired
    private StoryRepository repository;

    @Autowired
    private FinalVideoService service;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void resetRunner() {
        runner.reset();
    }

    @AfterEach
    void releaseAnyBlockedRender() {
        runner.release.countDown();
    }

    // --- flujo feliz ------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void happyPath_acceptsRunsAndPublishesTheMp4() throws Exception {
        Story story = readyStory("fv-ok");

        ResponseEntity<Map> accepted = rest.postForEntity(url("/stories/fv-ok/final-video"), null, Map.class);

        assertThat(accepted.getStatusCode().value()).isEqualTo(202);
        assertThat(accepted.getHeaders().getLocation()).hasToString("/stories/fv-ok/final-video");
        Map<String, Object> body = accepted.getBody();
        assertThat(body).containsEntry("storyId", "fv-ok").containsEntry("status", "PENDING").containsKey("jobId");
        String jobId = (String) body.get("jobId");

        Map<String, Object> done = waitForTerminal("fv-ok");
        assertThat(done.get("status")).isEqualTo("COMPLETED");
        assertThat(done.get("id")).isEqualTo(jobId);
        assertThat(done.get("output")).isEqualTo("fv-ok/final.mp4");
        assertThat(done.get("startedAt")).isNotNull();
        assertThat(done.get("finishedAt")).isNotNull();
        assertThat((List<Map<String, Object>>) done.get("stages")).extracting(s -> s.get("name"))
                .containsExactly("prepare", "transcribe", "generate", "lint", "check", "render", "validate");
        assertThat((List<Map<String, Object>>) done.get("warnings")).singleElement()
                .satisfies(w -> assertThat(w).containsEntry("stage", "lint"));

        Path finalVideo = dataDir.resolve("renders").resolve("fv-ok").resolve("final.mp4");
        assertThat(Files.readAllBytes(finalVideo)).isEqualTo(Mp4Fixtures.valid());

        // Lo que se le pasó a Node: rutas resueltas, timeouts y la historia serializada con SnapshotMapper.
        List<String> cmd = runner.makeVideoCalls().get(0);
        assertThat(cmd.get(0)).isEqualTo("node");
        assertThat(arg(cmd, "--clips-dir")).isEqualTo(dataDir.resolve("clips").toAbsolutePath().normalize().toString());
        assertThat(arg(cmd, "--audio-dir")).isEqualTo(dataDir.resolve("audio").toAbsolutePath().normalize().toString());
        assertThat(arg(cmd, "--render-timeout-sec")).isEqualTo("1800");
        assertThat(arg(cmd, "--transcribe-timeout-sec")).isEqualTo("600");
        assertThat(arg(cmd, "--check-timeout-sec")).isEqualTo("1200"); // default provisorio de application.yml
        assertThat(cmd).doesNotContain("--keep-work");
        Path work = Path.of(arg(cmd, "--work"));
        assertThat(work).isEqualTo(dataDir.resolve("renders").resolve("fv-ok").resolve("job-" + jobId).resolve("work").toAbsolutePath().normalize());
        String storyJson = Files.readString(Path.of(arg(cmd, "--story")));
        assertThat(storyJson).isEqualTo(objectMapper.writeValueAsString(SnapshotMapper.toMap(story.toSnapshot())));
        assertThat(storyJson).contains("\"targetDuration\":\"PT4.458S\"");

        // El MP4 por HTTP, con soporte de Range.
        ResponseEntity<byte[]> file = rest.getForEntity(url("/stories/fv-ok/final-video/file"), byte[].class);
        assertThat(file.getStatusCode().value()).isEqualTo(200);
        assertThat(file.getHeaders().getContentType()).hasToString("video/mp4");
        assertThat(file.getBody()).isEqualTo(Mp4Fixtures.valid());
        HttpHeaders range = new HttpHeaders();
        range.set(HttpHeaders.RANGE, "bytes=0-9");
        ResponseEntity<byte[]> partial = rest.exchange(url("/stories/fv-ok/final-video/file"), HttpMethod.GET, new HttpEntity<>(range), byte[].class);
        assertThat(partial.getStatusCode().value()).isEqualTo(206);
        assertThat(partial.getBody()).hasSize(10);
    }

    @Test
    @SuppressWarnings("unchecked")
    void whileRunning_theStatusShowsTheCurrentStageFromMakeVideo() throws Exception {
        readyStory("fv-progress");
        runner.mode = FakeProcessRunner.Mode.BLOCK;

        post("/stories/fv-progress/final-video", null, 202);
        assertThat(runner.started.await(10, TimeUnit.SECONDS)).isTrue();

        Map<String, Object> running = get("/stories/fv-progress/final-video", 200);
        assertThat(running.get("status")).isEqualTo("RUNNING");
        assertThat(running.get("stage")).isEqualTo("render");
        assertThat((List<Object>) running.get("stages")).hasSize(5);

        runner.release.countDown();
        assertThat(waitForTerminal("fv-progress").get("status")).isEqualTo("COMPLETED");
    }

    // --- fallos del proceso -------------------------------------------------------------------

    @Test
    void aFailedStageEndsFailedWithTheErrorAndABoundedTailOfItsLog() throws Exception {
        readyStory("fv-fail");
        runner.mode = FakeProcessRunner.Mode.FAIL;
        runner.failStage = "check";

        post("/stories/fv-fail/final-video", null, 202);
        Map<String, Object> done = waitForTerminal("fv-fail");

        assertThat(done.get("status")).isEqualTo("FAILED");
        assertThat(done.get("stage")).isEqualTo("check");
        assertThat((String) done.get("error")).contains("check encontró errores");
        String tail = (String) done.get("logTail");
        assertThat(tail).endsWith("detalle del fallo en check\n");
        assertThat(tail.getBytes()).hasSizeLessThanOrEqualTo(4096);
        assertThat(done.get("output")).isNull();
        assertThat(rest.getForEntity(url("/stories/fv-fail/final-video/file"), byte[].class).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void aTimedOutProcessEndsFailed() throws Exception {
        readyStory("fv-timeout");
        runner.mode = FakeProcessRunner.Mode.TIMEOUT;

        post("/stories/fv-timeout/final-video", null, 202);
        Map<String, Object> done = waitForTerminal("fv-timeout");

        assertThat(done.get("status")).isEqualTo("FAILED");
        assertThat(done.get("stage")).isEqualTo("render");
        assertThat((String) done.get("error")).contains("superó").contains("se canceló");
    }

    @Test
    void anInvalidProducedVideoIsNotPublished() throws Exception {
        readyStory("fv-bad-output");
        runner.video = Mp4Fixtures.of(1080, 1920).build();

        post("/stories/fv-bad-output/final-video", null, 202);
        Map<String, Object> done = waitForTerminal("fv-bad-output");

        assertThat(done.get("status")).isEqualTo("FAILED");
        assertThat(done.get("stage")).isEqualTo("publish");
        assertThat((String) done.get("error")).contains("1080x1920");
        assertThat(dataDir.resolve("renders").resolve("fv-bad-output").resolve("final.mp4")).doesNotExist();
    }

    // --- precondiciones (cada una por separado; ninguna lanza nada) -----------------------------

    @Test
    @SuppressWarnings("unchecked")
    void missingLocalClipFileIs409WithTheScene() throws Exception {
        readyStory("fv-noclip");
        Files.delete(dataDir.resolve("clips").resolve("fv-noclip-giro.mp4"));

        Map<String, Object> error = post("/stories/fv-noclip/final-video", null, 409);

        assertThat((Map<String, String>) error.get("scenes")).containsOnlyKeys("fv-noclip-giro");
        assertThat(runner.makeVideoCalls()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void clipThatFailsMp4ProbeIs422() throws Exception {
        readyStory("fv-badclip");
        Files.writeString(dataDir.resolve("clips").resolve("fv-badclip-gancho.mp4"), "<html>enlace vencido</html>");
        Files.write(dataDir.resolve("clips").resolve("fv-badclip-cierre.mp4"), Mp4Fixtures.of(1080, 1920).build());

        Map<String, Object> error = post("/stories/fv-badclip/final-video", null, 422);

        Map<String, String> scenes = (Map<String, String>) error.get("scenes");
        assertThat(scenes).containsOnlyKeys("fv-badclip-gancho", "fv-badclip-cierre");
        assertThat(scenes.get("fv-badclip-gancho")).contains("no es un MP4 válido");
        assertThat(scenes.get("fv-badclip-cierre")).contains("1080x1920");
        assertThat(runner.makeVideoCalls()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingNarrationAudioIs409() throws Exception {
        readyStory("fv-noaudio");
        Files.delete(dataDir.resolve("audio").resolve("fv-noaudio-cierre.mp3"));

        Map<String, Object> error = post("/stories/fv-noaudio/final-video", null, 409);

        assertThat((Map<String, String>) error.get("scenes")).containsOnlyKeys("fv-noaudio-cierre");
        assertThat(((Map<String, String>) error.get("scenes")).get("fv-noaudio-cierre")).contains("audio");
        assertThat(runner.makeVideoCalls()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void sceneNotCompletedIs409() throws Exception {
        List<Map<String, Object>> scenes = FinalVideoFixtures.readyScenes("fv-pending", clips(), audio());
        // El clip (de una generación anterior) sigue en disco y registrado: solo el estado impide el video.
        scenes.get(2).put("generationStatus", "IN_PROGRESS");
        repository.save(FinalVideoFixtures.story("fv-pending", scenes));

        Map<String, Object> error = post("/stories/fv-pending/final-video", null, 409);

        assertThat((Map<String, String>) error.get("scenes")).containsOnlyKeys("fv-pending-contexto");
        assertThat(((Map<String, String>) error.get("scenes")).get("fv-pending-contexto")).contains("IN_PROGRESS");
        assertThat(runner.makeVideoCalls()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void maliciousSceneIdsAre422AndNothingIsWrittenOutsideTheFolders() throws Exception {
        List<Map<String, Object>> scenes = FinalVideoFixtures.readyScenes("fv-evil", clips(), audio());
        scenes.get(0).put("id", "../../fuera");
        scenes.get(0).put("localClipPath", "../../fuera.mp4");
        repository.save(FinalVideoFixtures.story("fv-evil", scenes));

        Map<String, Object> error = post("/stories/fv-evil/final-video", null, 422);

        assertThat((Map<String, String>) error.get("scenes")).containsOnlyKeys("../../fuera");
        assertThat(runner.calls).isEmpty();
        assertThat(dataDir.resolve("renders").resolve("fv-evil")).doesNotExist();
    }

    @Test
    void maliciousStoryIdsNeverBecomePaths() {
        assertThat(post("/stories/mala.id/final-video", null, 422).get("error").toString()).contains("no permitido");
        assertThat(service.current("../x")).isEmpty();
        assertThat(service.current("..")).isEmpty();
        assertThat(rest.getForEntity(url("/stories/mala.id/final-video"), Map.class).getStatusCode().value()).isEqualTo(404);
        assertThat(runner.calls).isEmpty();
    }

    @Test
    void unknownStoryIs404() {
        post("/stories/no-existe/final-video", null, 404);
        assertThat(rest.getForEntity(url("/stories/no-existe/final-video"), Map.class).getStatusCode().value()).isEqualTo(404);
        assertThat(rest.getForEntity(url("/stories/no-existe/final-video/file"), Map.class).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void forceMustBeABoolean() {
        readyStory("fv-force-type");
        post("/stories/fv-force-type/final-video", Map.of("force", "sí"), 400);
        assertThat(runner.calls).isEmpty();
    }

    // --- 409 y force ---------------------------------------------------------------------------

    @Test
    void anExistingVideoIs409WithoutForce_forceReplacesIt_andAFailedReRenderKeepsTheOldFile() throws Exception {
        readyStory("fv-again");
        post("/stories/fv-again/final-video", null, 202);
        assertThat(waitForTerminal("fv-again").get("status")).isEqualTo("COMPLETED");
        Path finalVideo = dataDir.resolve("renders").resolve("fv-again").resolve("final.mp4");

        Map<String, Object> conflict = post("/stories/fv-again/final-video", null, 409);
        assertThat(conflict.get("error").toString()).contains("force");

        byte[] second = Mp4Fixtures.of(720, 1280).moovLast().build();
        runner.video = second;
        post("/stories/fv-again/final-video", Map.of("force", true), 202);
        assertThat(waitForTerminal("fv-again").get("status")).isEqualTo("COMPLETED");
        assertThat(Files.readAllBytes(finalVideo)).isEqualTo(second);
        // del trabajo anterior no queda carpeta: un trabajo vigente por historia
        try (var dirs = Files.list(finalVideo.getParent())) {
            assertThat(dirs.filter(p -> p.getFileName().toString().startsWith("job-")).count()).isEqualTo(1);
        }

        runner.mode = FakeProcessRunner.Mode.FAIL;
        post("/stories/fv-again/final-video", Map.of("force", true), 202);
        assertThat(waitForTerminal("fv-again").get("status")).isEqualTo("FAILED");
        assertThat(Files.readAllBytes(finalVideo)).as("el video anterior sigue en disco").isEqualTo(second);
    }

    @Test
    void aRenderInProgressOfAnyStoryIs409WithItsJobId() throws Exception {
        readyStory("fv-busy-a");
        readyStory("fv-busy-b");
        runner.mode = FakeProcessRunner.Mode.BLOCK;

        String jobA = (String) post("/stories/fv-busy-a/final-video", null, 202).get("jobId");
        assertThat(runner.started.await(10, TimeUnit.SECONDS)).isTrue();

        Map<String, Object> conflict = post("/stories/fv-busy-b/final-video", null, 409);
        assertThat(conflict.get("jobId")).isEqualTo(jobA);
        assertThat(conflict.get("error").toString()).contains("fv-busy-a");

        runner.release.countDown();
        assertThat(waitForTerminal("fv-busy-a").get("status")).isEqualTo("COMPLETED");
        assertThat(rest.getForEntity(url("/stories/fv-busy-b/final-video"), Map.class).getStatusCode().value()).isEqualTo(404);
        // terminado el anterior, se puede lanzar el siguiente
        runner.mode = FakeProcessRunner.Mode.SUCCESS;
        post("/stories/fv-busy-b/final-video", null, 202);
        assertThat(waitForTerminal("fv-busy-b").get("status")).isEqualTo("COMPLETED");
    }

    @Test
    void twoSimultaneousPostsStartOnlyOneRender() throws Exception {
        readyStory("fv-race");
        runner.mode = FakeProcessRunner.Mode.BLOCK;
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    gate.await();
                    return rest.postForEntity(url("/stories/fv-race/final-video"), null, Map.class).getStatusCode().value();
                }));
            }
            gate.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(202, 409);
        } finally {
            pool.shutdownNow();
        }
        runner.release.countDown();
        assertThat(waitForTerminal("fv-race").get("status")).isEqualTo("COMPLETED");
        assertThat(runner.makeVideoCalls()).hasSize(1);
    }

    // --- reinicio -------------------------------------------------------------------------------

    @Test
    void onStartupARunningJobIsMarkedFailed() throws Exception {
        Path dir = dataDir.resolve("renders").resolve("fv-restart");
        Files.createDirectories(dir);
        FinalVideoJob running = FinalVideoJob.pending("trabajo-viejo", "fv-restart", false).running();
        Files.writeString(dir.resolve("job.json"), objectMapper.writeValueAsString(running));

        service.recoverInterruptedJobs(); // lo mismo que hace FinalVideoConfiguration al crear el servicio

        Map<String, Object> job = get("/stories/fv-restart/final-video", 200);
        assertThat(job.get("status")).isEqualTo("FAILED");
        assertThat(job.get("id")).isEqualTo("trabajo-viejo");
        assertThat(job.get("error").toString()).contains("reinicio");
    }

    // --- helpers --------------------------------------------------------------------------------

    private Story readyStory(String storyId) {
        Story story = FinalVideoFixtures.story(storyId, FinalVideoFixtures.readyScenes(storyId, clips(), audio()));
        repository.save(story);
        return story;
    }

    private Path clips() {
        return dataDir.resolve("clips");
    }

    private Path audio() {
        return dataDir.resolve("audio");
    }

    private static String arg(List<String> cmd, String flag) {
        return cmd.get(cmd.indexOf(flag) + 1);
    }

    private Map<String, Object> waitForTerminal(String storyId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            Map<String, Object> job = get("/stories/" + storyId + "/final-video", 200);
            if ("COMPLETED".equals(job.get("status")) || "FAILED".equals(job.get("status"))) {
                // el hilo libera la guarda justo después de guardar el estado final
                Thread.sleep(50);
                return job;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("el trabajo de " + storyId + " no terminó en 20 s");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path, int expectedStatus) {
        ResponseEntity<Map> response = rest.getForEntity(url(path), Map.class);
        assertThat(response.getStatusCode().value()).as("GET %s: %s", path, response.getBody()).isEqualTo(expectedStatus);
        return (Map<String, Object>) response.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Object body, int expectedStatus) {
        ResponseEntity<Map> response = rest.postForEntity(url(path), body, Map.class);
        assertThat(response.getStatusCode().value()).as("POST %s: %s", path, response.getBody()).isEqualTo(expectedStatus);
        return (Map<String, Object>) response.getBody();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @TestConfiguration
    static class FakeRunnerConfig {
        @Bean
        @Primary
        FakeProcessRunner fakeProcessRunner() {
            return new FakeProcessRunner();
        }
    }
}
