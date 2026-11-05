package com.aishorts.engine.drafts;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Costo estimado de un borrador a partir de su usage y de los precios de
 * app.drafts.pricing. Es una estimación local, no lo que factura la API:
 * sirve para comparar borradores y configuraciones.
 */
public final class DraftCost {

    private static final double MILLION = 1_000_000d;

    private DraftCost() {
    }

    /** USD, redondeado a 4 decimales. */
    public static double estimateUsd(DraftUsage usage, DraftsProperties.Pricing pricing) {
        if (usage == null || pricing == null) {
            return 0;
        }
        double usd = usage.inputTokens() * pricing.inputPerMtok() / MILLION
                + usage.outputTokens() * pricing.outputPerMtok() / MILLION
                + usage.cacheCreationInputTokens() * pricing.cacheWritePerMtok() / MILLION
                + usage.cacheReadInputTokens() * pricing.cacheReadPerMtok() / MILLION
                + usage.webSearchRequests() * pricing.searchPer1000() / 1_000d;
        return BigDecimal.valueOf(usd).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }
}
