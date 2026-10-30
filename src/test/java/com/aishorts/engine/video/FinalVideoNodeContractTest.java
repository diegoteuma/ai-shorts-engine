package com.aishorts.engine.video;

import com.aishorts.engine.clips.ClipStore;
import com.aishorts.engine.clips.Mp4Fixtures;
import com.aishorts.engine.persistence.JsonFileStoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Contrato Java <-> Node: FinalVideoService con el SystemProcessRunner real
 * lanza el make-video.mjs real (con la CLI de HyperFrames y ffprobe simulados
 * de video-renderer/test/support) y entiende su make-video-result.json.
 * Se omite si no hay {@code node} en el PATH.
 */
class FinalVideoNodeContractTest {

    private static final Path RENDERER = Path.of("video-renderer").toAbsolutePath().normalize();
    private static final List<String> TUNGUSKA = List.of("gancho", "explicacion", "contexto", "giro", "consecuencia", "cierre");

    @TempDir
    Path tmp;

    @ParameterizedTest(name = "checkEnabled={0}")
    @ValueSource(booleans = {true, false})
    @SuppressWarnings("unchecked")
    void theRealMakeVideoScriptProducesAResultTheServiceUnderstands(boolean checkEnabled) throws Exception {
        assumeTrue(nodeAvailable(), "node no está en el PATH");
        ObjectMapper mapper = new ObjectMapper();
        Path clips = tmp.resolve("clips");
        Path audio = tmp.resolve("audio");
        Path renders = tmp.resolve("renders");

        // La historia del fixture de Node (las transcripciones simuladas corresponden a esta narración).
        Map<String, Object> fixture = mapper.readValue(RENDERER.resolve("test/fixtures/story.backend.json").toFile(), Map.class);
        List<Map<String, Object>> scenes = new ArrayList<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) fixture.get("scenes")) {
            String id = (String) s.get("id");
            scenes.add(FinalVideoFixtures.scene(id, (String) s.get("role"), (Integer) s.get("order"),
                    (String) s.get("narrationText"), (String) s.get("targetDuration")));
            FinalVideoFixtures.writeMedia(id, clips, audio);
        }
        assertThat(scenes).extracting(s -> s.get("id")).containsExactlyElementsOf(TUNGUSKA.stream().map(r -> "tunguska-" + r).toList());
        FinalVideoJob done = render(FinalVideoFixtures.story("tunguska", scenes), clips, audio, renders, checkEnabled);

        assertThat(done.status()).as("error: %s\n%s", done.error(), done.logTail()).isEqualTo(FinalVideoStatus.COMPLETED);
        assertThat(done.stages()).extracting(FinalVideoJob.StageTiming::name)
                .containsExactly("prepare", "transcribe", "generate", "lint", "check", "render", "validate");
        // Con check deshabilitado, Node recibe --skip-check y lo informa como etapa omitida.
        assertThat(done.stages()).allSatisfy(s -> {
            boolean skipped = !checkEnabled && s.name().equals("check");
            assertThat(s.skipped()).isEqualTo(skipped ? Boolean.TRUE : null);
            assertThat(s.exitCode()).isEqualTo(skipped ? null : 0);
            assertThat(s.ms()).isNotNull();
            if (skipped) {
                assertThat(s.ms()).isZero();
            }
        });
        assertThat(done.warnings()).anySatisfy(w -> {
            assertThat(w.stage()).isEqualTo("generate");
            assertThat(w.message()).contains("tiempo inferido");
        });
        assertThat(done.output()).isEqualTo("tunguska/final.mp4");
        assertThat(Files.readAllBytes(renders.resolve("tunguska/final.mp4"))).isEqualTo(Mp4Fixtures.valid());
    }

    /**
     * hookText y clipStartOffset llegan al story.json que lee make-video.mjs y de ahí al index.html que genera.
     * Con clipStartOffset > 0 generate.mjs mide el clip con el ffprobe del sistema (no el simulado), así que
     * usa los clips reales de tunguska (data/clips, versionados) y se omite sin ffprobe.
     */
    @org.junit.jupiter.api.Test
    @SuppressWarnings("unchecked")
    void hookTextAndClipStartOffsetReachTheJsonAndTheCompositionOfMakeVideo() throws Exception {
        assumeTrue(nodeAvailable(), "node no está en el PATH");
        assumeTrue(commandAvailable("ffprobe", "-version"), "ffprobe no está en el PATH");
        ObjectMapper mapper = new ObjectMapper();
        Path clips = tmp.resolve("clips");
        Path audio = tmp.resolve("audio");
        Path renders = tmp.resolve("renders");

        Map<String, Object> fixture = mapper.readValue(RENDERER.resolve("test/fixtures/story.backend.json").toFile(), Map.class);
        List<Map<String, Object>> scenes = new ArrayList<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) fixture.get("scenes")) {
            String id = (String) s.get("id");
            scenes.add(FinalVideoFixtures.scene(id, (String) s.get("role"), (Integer) s.get("order"),
                    (String) s.get("narrationText"), (String) s.get("targetDuration")));
            FinalVideoFixtures.writeMedia(id, clips, audio);
            Files.copy(Path.of("data", "clips", id + ".mp4"), clips.resolve(id + ".mp4"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        com.aishorts.engine.domain.Story story = FinalVideoFixtures.story("tunguska", scenes).withHookText("EXPLOSIÓN SIN CRÁTER");
        story.scenes().get(0).setClipStartOffset(0.5); // tunguska-gancho: clip de 5.000 s, escena de 4.458 s

        FinalVideoJob done = render(story, clips, audio, renders, false);

        assertThat(done.status()).as("error: %s\n%s", done.error(), done.logTail()).isEqualTo(FinalVideoStatus.COMPLETED);
        Path jobDir;
        try (var dirs = Files.list(renders.resolve("tunguska"))) {
            jobDir = dirs.filter(p -> p.getFileName().toString().startsWith("job-")).findFirst().orElseThrow();
        }
        Map<String, Object> sentToNode = mapper.readValue(jobDir.resolve("story.json").toFile(), Map.class);
        assertThat(sentToNode.get("hookText")).isEqualTo("EXPLOSIÓN SIN CRÁTER");
        List<Map<String, Object>> sentScenes = (List<Map<String, Object>>) sentToNode.get("scenes");
        assertThat(sentScenes).extracting(s -> ((Number) s.get("clipStartOffset")).doubleValue())
                .containsExactly(0.5, 0.0, 0.0, 0.0, 0.0, 0.0);

        String html = Files.readString(jobDir.resolve("work").resolve("index.html"));
        assertThat(html).contains("<div id=\"hook-text-box\">EXPLOSIÓN SIN CRÁTER</div>");
        assertThat(html).containsPattern("id=\"v-tunguska-gancho\"[^>]*data-media-start=\"0.5\"");
        assertThat(html).containsPattern("id=\"v-tunguska-explicacion\"[^>]*data-media-start=\"0\"");
    }

    /** FinalVideoService con el make-video.mjs real y la CLI de HyperFrames y ffprobe simulados; espera a que termine. */
    private FinalVideoJob render(com.aishorts.engine.domain.Story story, Path clips, Path audio, Path renders, boolean checkEnabled)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonFileStoryRepository repository = new JsonFileStoryRepository(tmp.resolve("stories"), mapper);
        repository.save(story);

        Path renderedVideo = tmp.resolve("render-simulado.mp4");
        Files.write(renderedVideo, Mp4Fixtures.valid());
        // Por entorno y no como argumentos: en Windows ProcessBuilder no escapa las comillas de un JSON.
        Map<String, String> fakeEnv = Map.of(
                "MAKE_VIDEO_HYPERFRAMES_CMD", mapper.writeValueAsString(List.of("node", RENDERER.resolve("test/support/fake-hyperframes.mjs").toString())),
                "MAKE_VIDEO_FFPROBE_CMD", mapper.writeValueAsString(List.of("node", RENDERER.resolve("test/support/fake-ffprobe.mjs").toString())),
                "FAKE_HF", mapper.writeValueAsString(Map.of(
                        "transcripts", RENDERER.resolve("test/fixtures/transcripts").toString(),
                        "renderFrom", renderedVideo.toString())),
                "FAKE_FFPROBE", "{}");
        SystemProcessRunner real = new SystemProcessRunner();
        ProcessRunner withFakeCli = (command, dir, env, timeout, log) -> {
            Map<String, String> allEnv = new HashMap<>(env);
            allEnv.putAll(fakeEnv);
            return real.run(command, dir, allEnv, timeout, log);
        };

        FinalVideoProperties props = new FinalVideoProperties(true, renders.toString(), RENDERER.toString(), "node", 1, false,
                checkEnabled, new FinalVideoProperties.Timeouts(60, 120, 60, 60, 60));
        ClipStore clipStore = new ClipStore(clips, (url, target) -> {
            throw new AssertionError("no debe descargar nada");
        }, 720, 1280, 1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            FinalVideoService service = new FinalVideoService(props, repository, clipStore, audio, withFakeCli, mapper, executor, 720, 1280);

            service.start(story.id(), false);
            FinalVideoJob done = waitForTerminal(service, story.id());
            if (done.status() == FinalVideoStatus.COMPLETED) {
                assertThat(service.finalVideo(story.id())).isPresent();
            }
            return done;
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    private static FinalVideoJob waitForTerminal(FinalVideoService service, String storyId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(180).toNanos();
        while (System.nanoTime() < deadline) {
            FinalVideoJob job = service.current(storyId).orElseThrow();
            if (!job.isActive()) {
                return job;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("make-video no terminó en 180 s");
    }

    private static boolean nodeAvailable() {
        return commandAvailable("node", "--version");
    }

    private static boolean commandAvailable(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
