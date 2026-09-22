package com.aishorts.engine.higgsfield;

import java.util.NavigableSet;
import java.util.Objects;

/**
 * Cómo un modelo de Higgsfield acepta el parámetro "duration". No todos los
 * modelos usan el mismo esquema:
 *
 * - {@link DiscreteValues}: un enum fijo de valores (ej. Kling 2.5 Turbo Pro
 *   solo admite 5 o 10, según su schema en el spec).
 * - {@link ContinuousRange}: cualquier entero dentro de [min, max] (ej.
 *   Kling 3.0 Standard admite 3 a 15, sin lista fija de valores).
 *
 * Ambos casos redondean SIEMPRE hacia arriba — facturar de menos no es una
 * opción — y lanzan si la escena pide más de lo que el modelo admite.
 */
public sealed interface DurationPolicy {

    /** La duración real a facturar (>= requestedSeconds) para este modelo. */
    long roundUpToAllowedDuration(long requestedSeconds);

    record DiscreteValues(NavigableSet<Long> allowedSeconds) implements DurationPolicy {
        public DiscreteValues {
            Objects.requireNonNull(allowedSeconds, "allowedSeconds");
            if (allowedSeconds.isEmpty()) {
                throw new IllegalArgumentException("allowedSeconds no puede estar vacío.");
            }
        }

        @Override
        public long roundUpToAllowedDuration(long requestedSeconds) {
            Long candidate = allowedSeconds.ceiling(requestedSeconds);
            if (candidate == null) {
                throw new IllegalArgumentException(
                        "pide " + requestedSeconds + "s pero este modelo solo admite hasta "
                                + allowedSeconds.last() + "s (duraciones permitidas: " + allowedSeconds + ").");
            }
            return candidate;
        }
    }

    record ContinuousRange(long minSeconds, long maxSeconds) implements DurationPolicy {
        public ContinuousRange {
            if (minSeconds <= 0 || maxSeconds < minSeconds) {
                throw new IllegalArgumentException(
                        "Rango de duración inválido: min=" + minSeconds + " max=" + maxSeconds);
            }
        }

        @Override
        public long roundUpToAllowedDuration(long requestedSeconds) {
            if (requestedSeconds > maxSeconds) {
                throw new IllegalArgumentException(
                        "pide " + requestedSeconds + "s pero este modelo solo admite hasta " + maxSeconds + "s.");
            }
            return Math.max(minSeconds, requestedSeconds);
        }
    }
}
