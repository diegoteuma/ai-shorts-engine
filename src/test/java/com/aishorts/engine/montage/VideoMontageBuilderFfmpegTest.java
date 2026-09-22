package com.aishorts.engine.montage;

import com.aishorts.engine.difficulty.TierRecommendation;
import com.aishorts.engine.domain.GenerationTier;
import com.aishorts.engine.domain.Scene;
import com.aishorts.engine.domain.SceneRole;
import com.aishorts.engine.domain.Story;
import com.aishorts.engine.higgsfield.EstimateResponse;
import com.aishorts.engine.subtitles.SrtGenerator;
import com.aishorts.engine.subtitles.SubtitleCue;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ejercita {@link VideoMontageBuilder} contra un ffmpeg REAL, con medios
 * sintéticos generados con {@code ffmpeg -f lavfi} — no contra los Fake* de
 * la demo, que a propósito devuelven bytes de relleno no reproducibles (ver
 * DemoRunner). Esto reproduce automatizado lo que el README documenta como
 * probado a mano ("Montaje/quemado, ya implementado, con ffmpeg").
 *
 * El clip de cada escena se sirve por un {@link HttpServer} local para
 * ejercitar {@link AssetDownloader} de punta a punta, igual que contra
 * Higgsfield real. El .srt se escribe a propósito en un directorio cuyo
 * nombre tiene un apóstrofe: si {@link VideoMontageBuilder} dejara de
 * copiarlo a un nombre fijo ("captions.srt") antes de quemarlo, el filtro
 * {@code subtitles=} de ffmpeg no podría abrir ese archivo y este test
 * fallaría (ver el javadoc de {@code stageSubtitlesSafely}).
 *
 * Deshabilitado por default porque necesita ffmpeg/ffprobe instalados y en
 * el PATH — activar con {@code RUN_FFMPEG_TESTS=true}.
 */
//@EnabledIfEnvironmentVariable(named = "RUN_FFMPEG_TESTS", matches = "true")
class VideoMontageBuilderFfmpegTest {

    private static final SceneRole[] ROLES = {
            SceneRole.GANCHO, SceneRole.EXPLICACION, SceneRole.CONTEXTO,
            SceneRole.GIRO, SceneRole.CONSECUENCIA, SceneRole.CIERRE
    };
    private static final String[] COLORS = {"red", "orange", "yellow", "green", "blue", "purple"};

    @TempDir
    static Path assetsDir;

    @TempDir
    Path tempDir;

    private static HttpServer assetServer;
    private static int assetServerPort;

    @BeforeAll
    static void generateAssetsAndStartServer() throws Exception {
        for (int i = 0; i < ROLES.length; i++) {
            generateColorClip(assetsDir.resolve(sceneId(i) + ".mp4"), COLORS[i]);
        }

        assetServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        assetServer.createContext("/", VideoMontageBuilderFfmpegTest::serveAsset);
        assetServer.setExecutor(null);
        assetServer.start();
        assetServerPort = assetServer.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (assetServer != null) {
            assetServer.stop(0);
        }
    }

    @Test
    void buildsVerticalMp4WithBurnedSubtitles_evenWhenSrtPathHasAnApostrophe() throws Exception {
        List<Scene> scenes = new ArrayList<>();
        for (int i = 0; i < ROLES.length; i++) {
            scenes.add(readyToGenerateScene(i));
        }
        Story story = new Story("story-1", "topic de prueba", "título de prueba", scenes);

        Path srtDir = tempDir.resolve("Tunguska's captions");
        Files.createDirectories(srtDir);
        Path srtPath = srtDir.resolve("story.srt");
        String srt = SrtGenerator.toSrt(List.of(
                new SubtitleCue(Duration.ZERO, Duration.ofSeconds(ROLES.length), "Hola Tunguska")));
        Files.writeString(srtPath, srt, StandardCharsets.UTF_8);

        Path workDir = tempDir.resolve("work");
        Path outputPath = tempDir.resolve("output.mp4");
        VideoMontageBuilder builder = new VideoMontageBuilder(new FfmpegRunner(), new AssetDownloader());

        Path result = builder.build(story, workDir, srtPath, outputPath);

        assertThat(result).isEqualTo(outputPath);
        assertThat(Files.isRegularFile(outputPath)).isTrue();
        assertThat(Files.size(outputPath)).isPositive();
        // Prueba indirecta de que el fix de "copiar a captions.srt" corrió de
        // verdad, no que el filtro subtitles= tuvo suerte con el path original.
        assertThat(workDir.resolve("captions.srt")).exists();

        assertThat(probeResolution(outputPath)).containsExactly(1080, 1920);
    }

    // --- arma una Scene real, lista para montaje (COMPLETED + audio adjunto) ---------------

    private Scene readyToGenerateScene(int index) throws Exception {
        String id = sceneId(index);
        Scene scene = new Scene(id, ROLES[index], index + 1, "narración " + id, "prompt " + id, Duration.ofSeconds(1));

        scene.proposeTier(new TierRecommendation(GenerationTier.STANDARD, 20, "test"));
        scene.approvePrompt();

        Path narrationAudio = tempDir.resolve(id + "-narration.wav");
        generateSineAudio(narrationAudio);
        scene.attachNarrationAudio(narrationAudio.toString(), Duration.ofSeconds(1));

        scene.recordCostEstimate(new EstimateResponse(new BigDecimal("0.10"), "USD", "model", Map.of()), "model");
        scene.approveCost();

        scene.startGeneration("req-" + id, null);
        scene.completeGeneration("http://127.0.0.1:" + assetServerPort + "/" + id + ".mp4");

        return scene;
    }

    private static String sceneId(int index) {
        return "scene-" + (index + 1);
    }

    // --- servidor HTTP local que sirve los clips sintéticos, para ejercitar AssetDownloader ---

    private static void serveAsset(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String fileName = path.startsWith("/") ? path.substring(1) : path;
            Path file = assetsDir.resolve(fileName).normalize();
            if (!file.startsWith(assetsDir) || !Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] bytes = Files.readAllBytes(file);
            exchange.getResponseHeaders().add("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } finally {
            exchange.close();
        }
    }

    // --- generación de medios sintéticos con ffmpeg -f lavfi ------------------------------

    private static void generateColorClip(Path output, String color) throws Exception {
        runProcess(List.of(
                "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "color=c=" + color + ":s=320x240:d=1",
                "-c:v", "mpeg4", "-pix_fmt", "yuv420p", output.toString()));
    }

    private static void generateSineAudio(Path output) throws Exception {
        runProcess(List.of(
                "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
                "-c:a", "pcm_s16le", output.toString()));
    }

    private static int[] probeResolution(Path video) throws Exception {
        String out = runProcess(List.of(
                "ffprobe", "-v", "error", "-select_streams", "v:0",
                "-show_entries", "stream=width,height", "-of", "csv=p=0", video.toString()));
        String[] parts = out.trim().split(",");
        return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    }

    private static String runProcess(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("El comando no terminó a tiempo: " + command + "\n" + output);
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("El comando falló (" + process.exitValue() + "): " + command + "\n" + output);
        }
        return output;
    }
}
