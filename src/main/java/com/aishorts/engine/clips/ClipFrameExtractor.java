package com.aishorts.engine.clips;

import com.aishorts.engine.video.ProcessRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Extrae con FFmpeg 3 fotogramas JPG de un clip local, para revisar a ojo
 * (p. ej. el GANCHO) sin abrir el video: en {@code start}, {@code start + 1 s}
 * y {@code start + 2 s}, acotados a la duración real del clip.
 *
 * Solo lee el clip (FFmpeg lo abre como entrada); los JPG van a
 * {@code framesDir/<idHistoria>/<idEscena>-<milisegundos>ms.jpg} y se pisan en
 * cada llamada. framesDir tiene que estar fuera del control de versiones
 * (por defecto ./data/frames, ignorado por git).
 */
public final class ClipFrameExtractor {

    /** Segundos, relativos al inicio pedido, de cada fotograma. */
    static final double[] FRAME_OFFSETS_SECONDS = {0, 1, 2};
    /**
     * Un fotograma pedido exactamente en el último instante del clip no
     * existe (FFmpeg no escribe nada): el máximo es la duración menos este
     * margen, un poco más que un fotograma a 24 fps.
     */
    static final double END_MARGIN_SECONDS = 0.05;

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]+");

    /** Un fotograma: el segundo pedido, el que se usó (acotado) y el archivo escrito. */
    public record Frame(double requestedSeconds, double seconds, boolean clamped, Path path) {
    }

    private final Path framesDir;
    private final List<String> ffmpegCommand;
    private final ProcessRunner runner;
    private final Duration timeout;

    public ClipFrameExtractor(Path framesDir, List<String> ffmpegCommand, ProcessRunner runner, Duration timeout) {
        this.framesDir = Objects.requireNonNull(framesDir, "framesDir");
        this.ffmpegCommand = List.copyOf(ffmpegCommand);
        if (this.ffmpegCommand.isEmpty()) {
            throw new IllegalArgumentException("El comando de FFmpeg no puede estar vacío.");
        }
        this.runner = Objects.requireNonNull(runner, "runner");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    public Path framesDir() {
        return framesDir;
    }

    /**
     * Segundos donde se toman los fotogramas: start, start+1, start+2, cada
     * uno acotado a [0, clipSeconds - END_MARGIN_SECONDS]. Función pura.
     */
    public static List<double[]> plannedSeconds(double startSeconds, double clipSeconds) {
        if (!Double.isFinite(startSeconds) || startSeconds < 0) {
            throw new IllegalArgumentException("El segundo de inicio debe ser >= 0, llegó: " + startSeconds);
        }
        if (!Double.isFinite(clipSeconds) || clipSeconds <= 0) {
            throw new IllegalArgumentException("La duración del clip debe ser > 0, llegó: " + clipSeconds);
        }
        double max = Math.max(0, clipSeconds - END_MARGIN_SECONDS);
        List<double[]> planned = new ArrayList<>();
        for (double offset : FRAME_OFFSETS_SECONDS) {
            double requested = startSeconds + offset;
            planned.add(new double[]{requested, Math.min(requested, max)});
        }
        return planned;
    }

    /**
     * Escribe los 3 JPG. Lanza ClipException con un mensaje claro si FFmpeg
     * no se puede lanzar, falla, vence el tiempo o no deja el archivo.
     */
    public List<Frame> extract(String storyId, String sceneId, Path clip, double startSeconds, double clipSeconds) {
        requireSafe(storyId, "historia");
        requireSafe(sceneId, "escena");
        if (!Files.isRegularFile(clip)) {
            throw new ClipException("No existe el clip local '" + clip + "'.");
        }
        Path outDir = framesDir.resolve(storyId);
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new ClipException("No se pudo crear el directorio de fotogramas '" + outDir + "': " + e.getMessage(), e);
        }
        Path log = outDir.resolve(sceneId + "-ffmpeg.log");
        List<Frame> frames = new ArrayList<>();
        for (double[] plan : plannedSeconds(startSeconds, clipSeconds)) {
            double seconds = plan[1];
            Path out = outDir.resolve(String.format(Locale.ROOT, "%s-%dms.jpg", sceneId, Math.round(seconds * 1000)));
            List<String> command = new ArrayList<>(ffmpegCommand);
            // -ss antes de -i: búsqueda rápida y exacta en FFmpeg moderno. -y pisa el JPG anterior.
            command.addAll(List.of("-hide_banner", "-loglevel", "error", "-y",
                    "-ss", String.format(Locale.ROOT, "%.3f", seconds),
                    "-i", clip.toAbsolutePath().toString(),
                    "-frames:v", "1", "-q:v", "2",
                    out.toAbsolutePath().toString()));
            try {
                Files.deleteIfExists(out);
            } catch (IOException e) {
                throw new ClipException("No se pudo borrar el fotograma anterior '" + out + "': " + e.getMessage(), e);
            }
            ProcessRunner.Result result;
            try {
                result = runner.run(command, outDir, Map.of(), timeout, log);
            } catch (RuntimeException e) {
                throw new ClipException("No se pudo ejecutar FFmpeg ('" + ffmpegCommand.get(0) + "'): " + e.getMessage(), e);
            }
            if (result.timedOut()) {
                throw new ClipException("FFmpeg no terminó en " + timeout.toSeconds() + " s extrayendo el segundo "
                        + format(seconds) + " de '" + sceneId + "' (ver " + log + ").");
            }
            if (result.exitCode() != 0) {
                throw new ClipException("FFmpeg falló (código " + result.exitCode() + ") extrayendo el segundo "
                        + format(seconds) + " de '" + sceneId + "' (ver " + log + ").");
            }
            if (!Files.isRegularFile(out)) {
                throw new ClipException("FFmpeg terminó bien pero no escribió '" + out + "' (segundo " + format(seconds)
                        + "; ver " + log + ").");
            }
            frames.add(new Frame(plan[0], seconds, seconds != plan[0], out));
        }
        return frames;
    }

    private static String format(double seconds) {
        return String.format(Locale.ROOT, "%.3f", seconds);
    }

    private static void requireSafe(String id, String what) {
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            throw new ClipException("El id de " + what + " '" + id + "' tiene caracteres no permitidos para un nombre de archivo.");
        }
    }
}
