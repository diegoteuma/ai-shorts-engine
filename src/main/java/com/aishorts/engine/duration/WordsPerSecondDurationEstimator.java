package com.aishorts.engine.duration;

import java.time.Duration;

/**
 * Estimador simple por ritmo de habla: palabras / (palabras por segundo).
 *
 * El default de 2.6 palabras/segundo (~156 palabras/minuto) es un ritmo
 * típico de narración documental en español neutro, ni apurado ni
 * declamado. Es un valor de partida razonable, no una medición — conviene
 * calibrarlo contra el audio real en cuanto haya narraciones de muestra del
 * proveedor de TTS elegido, y ese es el motivo por el que el ritmo es un
 * parámetro del constructor y no una constante fija en el código.
 */
public final class WordsPerSecondDurationEstimator implements NarrationDurationEstimator {

    private final double wordsPerSecond;

    public WordsPerSecondDurationEstimator(double wordsPerSecond) {
        if (wordsPerSecond <= 0) {
            throw new IllegalArgumentException("wordsPerSecond debe ser positivo: " + wordsPerSecond);
        }
        this.wordsPerSecond = wordsPerSecond;
    }

    public static WordsPerSecondDurationEstimator neutralSpanish() {
        return new WordsPerSecondDurationEstimator(2.6);
    }

    @Override
    public Duration estimate(String narrationText) {
        int wordCount = countWords(narrationText);
        double seconds = wordCount / wordsPerSecond;
        long millis = Math.round(seconds * 1000);
        return Duration.ofMillis(millis);
    }

    private static int countWords(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        return trimmed.split("\\s+").length;
    }
}
