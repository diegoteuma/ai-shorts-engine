package com.aishorts.engine.subtitles;

import java.time.Duration;
import java.util.Objects;

/** Un segmento de subtítulo con su ventana de tiempo exacta dentro del video. */
public record SubtitleCue(Duration start, Duration end, String text) {
    public SubtitleCue {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(text, "text");
        if (end.compareTo(start) <= 0) {
            throw new IllegalArgumentException("end debe ser posterior a start: " + start + " -> " + end);
        }
    }
}
