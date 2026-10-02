package com.aishorts.engine.montage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Medios sintéticos pero reales (reproducibles por ffmpeg), generados con
 * {@code ffmpeg -f lavfi}: clips de color sólido y tonos senoidales. Los
 * comparten los tests que corren ffmpeg de verdad (VideoMontageBuilderFfmpegTest,
 * StoryMontageServiceTest, StoryMontageApiTest). Necesitan ffmpeg/ffprobe en el PATH.
 */
public final class SyntheticMedia {

    private SyntheticMedia() {
    }

    /** Clip de 1s de color sólido, 320x240 — a propósito no 9:16, para ejercitar el scale+pad. */
    public static void generateColorClip(Path output, String color) throws Exception {
        runProcess(List.of(
                "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "color=c=" + color + ":s=320x240:d=1",
                "-c:v", "mpeg4", "-pix_fmt", "yuv420p", output.toString()));
    }

    /** Tono de 1s; el códec sale de la extensión (.mp3 -> mp3 real, como el que guarda la narración). */
    public static void generateSineAudio(Path output) throws Exception {
        String codec = output.toString().endsWith(".mp3") ? "libmp3lame" : "pcm_s16le";
        runProcess(List.of(
                "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
                "-c:a", codec, output.toString()));
    }

    public static int[] probeResolution(Path video) throws Exception {
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
