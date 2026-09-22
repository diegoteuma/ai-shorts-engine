package com.aishorts.engine.subtitles;

import java.time.Duration;
import java.util.List;

/**
 * Genera un archivo .srt a partir de las ventanas de tiempo de la narración.
 *
 * Esto es un bloque de construcción neutral: sirve tanto si al final se
 * decide entregar el .srt como archivo aparte, como si se decide quemar el
 * subtítulo en el video (necesitás el texto con timing exacto en cualquiera
 * de los dos casos), como si se decide confiar en el auto-caption de
 * YouTube (en ese caso el .srt igual sirve de respaldo/control de calidad).
 * La decisión de cuál de las tres opciones usar todavía está pendiente y no
 * se resuelve acá.
 */
public final class SrtGenerator {

    private SrtGenerator() {
    }

    public static String toSrt(List<SubtitleCue> cues) {
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (SubtitleCue cue : cues) {
            sb.append(index++).append('\n');
            sb.append(formatTimestamp(cue.start())).append(" --> ").append(formatTimestamp(cue.end())).append('\n');
            sb.append(cue.text()).append('\n');
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String formatTimestamp(Duration duration) {
        long totalMillis = duration.toMillis();
        long hours = totalMillis / 3_600_000;
        long minutes = (totalMillis % 3_600_000) / 60_000;
        long seconds = (totalMillis % 60_000) / 1_000;
        long millis = totalMillis % 1_000;
        return String.format("%02d:%02d:%02d,%03d", hours, minutes, seconds, millis);
    }
}
