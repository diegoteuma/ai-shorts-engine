package com.aishorts.engine.higgsfield;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Tarifa de un modelo de Higgsfield, publicada en su consola — no en la API:
 * el spec público (docs.higgsfield.ai/docs/openapi.json) no tiene ningún
 * endpoint de estimate, así que el costo se calcula localmente contra esto
 * en vez de pegarle a la red (ver {@link HiggsfieldRestClient#estimateCost}).
 *
 * durationPolicy encapsula cómo ese modelo en particular acepta "duration"
 * — enum fijo o rango continuo, ver {@link DurationPolicy} — porque no todos
 * los modelos usan el mismo esquema (Kling 2.5 Turbo Pro vs. Kling 3.0
 * Standard, por ejemplo).
 */
public record ModelPricing(BigDecimal pricePerSecond, DurationPolicy durationPolicy, String currency) {
    public ModelPricing {
        Objects.requireNonNull(pricePerSecond, "pricePerSecond");
        Objects.requireNonNull(durationPolicy, "durationPolicy");
        Objects.requireNonNull(currency, "currency");
    }

    /**
     * La duración real a facturar (>= requestedSeconds), redondeada hacia
     * arriba según la política de este modelo. Lanza si la escena pide más
     * de lo que el modelo admite — no hay forma correcta de facturar eso
     * silenciosamente contra el máximo.
     */
    public long roundUpToAllowedDuration(long requestedSeconds) {
        try {
            return durationPolicy.roundUpToAllowedDuration(requestedSeconds);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("La escena " + e.getMessage(), e);
        }
    }

    /** Costo de facturar requestedSeconds, ya redondeado hacia arriba según durationPolicy. */
    public BigDecimal costFor(long requestedSeconds) {
        return pricePerSecond.multiply(BigDecimal.valueOf(roundUpToAllowedDuration(requestedSeconds)));
    }
}
