package com.aishorts.engine.higgsfield;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Tarifas de Higgsfield confirmadas contra su consola (no contra la API —
 * no existe un endpoint de estimate real, ver {@link HiggsfieldRestClient#estimateCost}).
 * Cargar acá cada modelo a medida que se confirme su $/segundo y su
 * {@link DurationPolicy}.
 *
 * Los modelId son literales del catálogo real y tienen que coincidir exacto
 * con lo que se configure en HIGGSFIELD_MODEL_STANDARD / HIGGSFIELD_MODEL_PREMIUM
 * para que {@link HiggsfieldConfig#pricingFor} los encuentre — ver STANDARD_MODEL_ID
 * / PREMIUM_MODEL_ID acá abajo, para no repetir el literal en otro lado.
 */
public final class KnownHiggsfieldPricing {

    /** Kling 2.5 Turbo Pro — confirmado para el piloto. */
    public static final String STANDARD_MODEL_ID = "kling-video/v2.5-turbo/pro/text-to-video";

    /** Kling 3.0 Standard — confirmado para el piloto. */
    public static final String PREMIUM_MODEL_ID = "kling-video/v3.0/std/text-to-video";

    private KnownHiggsfieldPricing() {
    }

    public static Map<String, ModelPricing> defaults() {
        Map<String, ModelPricing> pricing = new LinkedHashMap<>();

        // STANDARD — Kling 2.5 Turbo Pro. duration es un enum fijo: solo 5 o
        // 10 (confirmado contra el schema real del modelo en el spec). No
        // tiene un parámetro de audio propio (su schema solo acepta
        // prompt/duration/cfg_scale/negative_prompt), así que no necesita el
        // fix de "sound": "off" que sí necesita PREMIUM — ver
        // StoryApprovalService#buildGenerationParameters.
        pricing.put(STANDARD_MODEL_ID, new ModelPricing(
                new BigDecimal("0.021"),
                new DurationPolicy.DiscreteValues(new TreeSet<>(List.of(5L, 10L))),
                "USD"));

        // PREMIUM — Kling 3.0 Standard. duration es continua entre 3 y 15,
        // sin enum fijo -- a diferencia de STANDARD, cualquier entero en ese
        // rango es válido.
        pricing.put(PREMIUM_MODEL_ID, new ModelPricing(
                new BigDecimal("0.0714"),
                new DurationPolicy.ContinuousRange(3, 15),
                "USD"));

        return pricing;
    }
}
