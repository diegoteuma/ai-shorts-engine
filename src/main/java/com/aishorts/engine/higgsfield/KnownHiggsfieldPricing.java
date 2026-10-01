package com.aishorts.engine.higgsfield;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

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
 *
 * STANDARD_MODEL_ID y PREMIUM_MODEL_ID apuntan al MISMO modelo a propósito:
 * Seedance 2.5 no aporta nada sobre 2.0 a 720p y es más caro, así que los dos
 * tiers comparten una sola entrada de pricing/DurationPolicy. El concepto de
 * tier en el dominio (Scene.chosenTier, la puerta 1) sigue existiendo —
 * ver DefaultSceneDifficultyScorer — solo que ahora ambos tiers resuelven al
 * mismo model_id de Higgsfield.
 */
public final class KnownHiggsfieldPricing {

    public static final String STANDARD_MODEL_ID = "bytedance/seedance-2.0/text-to-video";

    public static final String PREMIUM_MODEL_ID = "bytedance/seedance-2.0/text-to-video";

    /** Wan 3.0 Prime (Alibaba) — ver el entry de abajo en defaults() para el pricing/duración real. */
    public static final String WAN_PRIME_MODEL_ID = "alibaba/wan-3.0-prime/text-to-video";

    private KnownHiggsfieldPricing() {
    }

    public static Map<String, ModelPricing> defaults() {
        Map<String, ModelPricing> pricing = new LinkedHashMap<>();

        // Seedance 2.0 Text to Video — único modelo, usado para STANDARD y
        // PREMIUM. duration continua 4-15s (confirmado contra la doc de la
        // consola de Higgsfield, el spec público openapi.json ni siquiera
        // lista este modelo). resolution se fija a "720p" en
        // StoryApprovalService#buildGenerationParameters.
        //
        // TODO: pricePerSecond SIN CONFIRMAR para 720p específico — la consola
        // solo publica el rango completo por resolución (480p-4k: $0.0985 -
        // $1.0887/s), no el precio por resolución individual. Este valor solo
        // afecta el estimado LOCAL que se muestra en la puerta 2
        // (HiggsfieldRestClient#estimateCost no pega a la red), nunca lo que
        // Higgsfield factura de verdad — confirmar corriendo una generación
        // real mínima (duration=4) a 720p y leyendo el cargo real en el usage
        // de Higgsfield, después reemplazar este placeholder.
        pricing.put(STANDARD_MODEL_ID, new ModelPricing(
                new BigDecimal("0.35"),
                new DurationPolicy.ContinuousRange(4, 15),
                "USD"));

        // Wan 3.0 Prime (Alibaba) Text to Video — duration continua 2-30s.
        // Igual que Seedance arriba, no hay una sola key compartida con
        // Seedance porque son modelId distintos; si algún día STANDARD y
        // PREMIUM volvieran a apuntar al mismo id entre sí, pricing.put()
        // sobre este LinkedHashMap simplemente pisa la entrada anterior sin
        // tirar excepción (no se arma con Map.of/Collectors.toMap, que sí
        // explotarían con keys duplicadas).
        //
        // TODO: pricePerSecond SIN CONFIRMAR contra el cargo real de
        // Higgsfield — Higgsfield no publica precio por resolución para este
        // modelo. $0.14/s a 720p es una referencia tomada de fal/agregadores
        // externos, no de la consola de Higgsfield. Verificar corriendo una
        // generación real mínima (duration=2) a 720p y leyendo el cargo real
        // en el usage de Higgsfield, después reemplazar este placeholder.
        pricing.put(WAN_PRIME_MODEL_ID, new ModelPricing(
                new BigDecimal("0.14"),
                new DurationPolicy.ContinuousRange(2, 30),
                "USD"));

        return pricing;
    }
}
