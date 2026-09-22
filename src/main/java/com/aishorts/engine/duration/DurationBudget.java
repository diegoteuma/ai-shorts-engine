package com.aishorts.engine.duration;

import java.time.Duration;

/**
 * Rango objetivo de duración total del Short (ej. 35–40 segundos). Se valida
 * contra la suma de las duraciones estimadas de las 6 escenas, tanto al
 * armar el primer borrador como cada vez que se revisa una narración en la
 * puerta 1.
 */
public record DurationBudget(Duration min, Duration max) {
    public DurationBudget {
        if (min.isNegative() || max.compareTo(min) < 0) {
            throw new IllegalArgumentException("Rango de duración inválido: min=" + min + " max=" + max);
        }
    }

    public static DurationBudget ofSeconds(long minSeconds, long maxSeconds) {
        return new DurationBudget(Duration.ofSeconds(minSeconds), Duration.ofSeconds(maxSeconds));
    }

    public boolean contains(Duration value) {
        return value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
    }
}
