package com.aishorts.engine.clips;

import com.aishorts.engine.video.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ClipFrameExtractor sin FFmpeg real: un ProcessRunner falso escribe (o no) el JPG que FFmpeg escribiría. */
class ClipFrameExtractorTest {

    @TempDir
    Path tmp;

    @Test
    void plannedSeconds_areStartPlusZeroOneTwo_whenTheClipIsLongEnough() {
        List<double[]> planned = ClipFrameExtractor.plannedSeconds(3.0, 8.0);

        assertThat(planned).extracting(p -> p[0]).containsExactly(3.0, 4.0, 5.0);
        assertThat(planned).extracting(p -> p[1]).containsExactly(3.0, 4.0, 5.0);
    }

    @Test
    void plannedSeconds_areClampedToTheClipDurationMinusTheMargin() {
        // Clip de 4 s con inicio en 3 s: 3, 4 -> 3.95, 5 -> 3.95.
        List<double[]> planned = ClipFrameExtractor.plannedSeconds(3.0, 4.0);

        assertThat(planned).extracting(p -> p[0]).containsExactly(3.0, 4.0, 5.0);
        assertThat(planned).extracting(p -> p[1]).containsExactly(3.0, 3.95, 3.95);
        // Un inicio más allá del final también queda acotado (nunca un segundo inexistente).
        assertThat(ClipFrameExtractor.plannedSeconds(10.0, 4.0)).extracting(p -> p[1]).containsOnly(3.95);
        // Un clip más corto que el margen: todo en 0.
        assertThat(ClipFrameExtractor.plannedSeconds(0, 0.02)).extracting(p -> p[1]).containsOnly(0.0);
    }

    @Test
    void plannedSeconds_rejectsNegativeStartAndNonPositiveDuration() {
        assertThatThrownBy(() -> ClipFrameExtractor.plannedSeconds(-0.5, 6)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClipFrameExtractor.plannedSeconds(Double.NaN, 6)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClipFrameExtractor.plannedSeconds(0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extract_runsFfmpegOncePerFrame_andReturnsTheWrittenJpgs() throws IOException {
        Path clip = writeClip();
        RecordingRunner runner = new RecordingRunner(0, true);
        ClipFrameExtractor extractor = new ClipFrameExtractor(tmp.resolve("frames"), List.of("ffmpeg"), runner, Duration.ofSeconds(5));

        List<ClipFrameExtractor.Frame> frames = extractor.extract("valdivia-1960", "valdivia-1960-gancho", clip, 3.0, 4.0);

        assertThat(frames).extracting(ClipFrameExtractor.Frame::seconds).containsExactly(3.0, 3.95, 3.95);
        assertThat(frames).extracting(ClipFrameExtractor.Frame::clamped).containsExactly(false, true, true);
        assertThat(frames).allSatisfy(f -> {
            assertThat(Files.isRegularFile(f.path())).isTrue();
            assertThat(f.path().getParent()).isEqualTo(tmp.resolve("frames").resolve("valdivia-1960"));
        });
        assertThat(frames.get(0).path().getFileName().toString()).isEqualTo("valdivia-1960-gancho-3000ms.jpg");
        assertThat(runner.commands).hasSize(3);
        List<String> first = runner.commands.get(0);
        assertThat(first.get(0)).isEqualTo("ffmpeg");
        assertThat(first).containsSubsequence("-ss", "3.000", "-i", clip.toAbsolutePath().toString(), "-frames:v", "1");
        // El clip es solo entrada: nunca aparece después de "-i" como salida ni se modifica.
        assertThat(first.get(first.size() - 1)).endsWith(".jpg");
        assertThat(Files.readAllBytes(clip)).isEqualTo(Mp4Fixtures.valid());
    }

    @Test
    void extract_failsClearly_whenFfmpegExitsWithError() throws IOException {
        Path clip = writeClip();
        ClipFrameExtractor extractor = new ClipFrameExtractor(tmp.resolve("frames"), List.of("ffmpeg"),
                new RecordingRunner(1, false), Duration.ofSeconds(5));

        assertThatThrownBy(() -> extractor.extract("s", "s-gancho", clip, 0, 6))
                .isInstanceOf(ClipException.class).hasMessageContaining("FFmpeg falló (código 1)");
    }

    @Test
    void extract_failsClearly_whenFfmpegTimesOut_orCannotBeLaunched_orWritesNothing() throws IOException {
        Path clip = writeClip();
        ProcessRunner timeout = (c, w, e, t, l) -> new ProcessRunner.Result(-1, true);
        ProcessRunner missing = (c, w, e, t, l) -> {
            throw new IllegalStateException("No se pudo lanzar 'ffmpeg': not found");
        };

        assertThatThrownBy(() -> new ClipFrameExtractor(tmp.resolve("f1"), List.of("ffmpeg"), timeout, Duration.ofSeconds(5))
                .extract("s", "s-gancho", clip, 0, 6)).isInstanceOf(ClipException.class).hasMessageContaining("no terminó en 5 s");
        assertThatThrownBy(() -> new ClipFrameExtractor(tmp.resolve("f2"), List.of("ffmpeg"), missing, Duration.ofSeconds(5))
                .extract("s", "s-gancho", clip, 0, 6)).isInstanceOf(ClipException.class).hasMessageContaining("No se pudo ejecutar FFmpeg");
        assertThatThrownBy(() -> new ClipFrameExtractor(tmp.resolve("f3"), List.of("ffmpeg"), new RecordingRunner(0, false), Duration.ofSeconds(5))
                .extract("s", "s-gancho", clip, 0, 6)).isInstanceOf(ClipException.class).hasMessageContaining("no escribió");
    }

    @Test
    void extract_failsWithoutTheClip_andWithUnsafeIds() {
        ClipFrameExtractor extractor = new ClipFrameExtractor(tmp.resolve("frames"), List.of("ffmpeg"),
                new RecordingRunner(0, true), Duration.ofSeconds(5));

        assertThatThrownBy(() -> extractor.extract("s", "s-gancho", tmp.resolve("no-existe.mp4"), 0, 6))
                .isInstanceOf(ClipException.class).hasMessageContaining("No existe el clip local");
        assertThatThrownBy(() -> extractor.extract("../x", "s-gancho", tmp.resolve("no-existe.mp4"), 0, 6))
                .isInstanceOf(ClipException.class).hasMessageContaining("caracteres no permitidos");
    }

    private Path writeClip() throws IOException {
        Path clip = tmp.resolve("clip.mp4");
        Files.write(clip, Mp4Fixtures.valid());
        return clip;
    }

    /** Registra los comandos; con writeOutput escribe un JPG falso en el último argumento (la salida de FFmpeg). */
    static final class RecordingRunner implements ProcessRunner {
        final List<List<String>> commands = new ArrayList<>();
        private final int exitCode;
        private final boolean writeOutput;

        RecordingRunner(int exitCode, boolean writeOutput) {
            this.exitCode = exitCode;
            this.writeOutput = writeOutput;
        }

        @Override
        public Result run(List<String> command, Path workingDir, Map<String, String> extraEnv, Duration timeout, Path logFile) {
            commands.add(List.copyOf(command));
            if (writeOutput) {
                try {
                    Files.write(Path.of(command.get(command.size() - 1)), new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            return new Result(exitCode, false);
        }
    }
}
