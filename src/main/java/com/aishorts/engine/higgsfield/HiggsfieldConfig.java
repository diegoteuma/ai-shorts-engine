package com.aishorts.engine.higgsfield;

import com.aishorts.engine.domain.GenerationTier;

import java.util.Map;
import java.util.Objects;

/**
 * Configuración del cliente de Higgsfield: credenciales y el catálogo de
 * modelos por tier. El catálogo es lo único que hay que tocar para elegir
 * qué modelo concreto de Higgsfield usa STANDARD y cuál usa PREMIUM —
 * el resto del sistema solo conoce el tier, nunca un model_id hardcodeado.
 */
public record HiggsfieldConfig(
        String baseUrl,
        String apiKeyId,
        String apiKeySecret,
        Map<GenerationTier, String> modelCatalog,
        Map<String, ModelPricing> pricingByModelId
) {
    public HiggsfieldConfig {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(apiKeyId, "apiKeyId");
        Objects.requireNonNull(apiKeySecret, "apiKeySecret");
        Objects.requireNonNull(modelCatalog, "modelCatalog");
        Objects.requireNonNull(pricingByModelId, "pricingByModelId");
        if (!modelCatalog.containsKey(GenerationTier.STANDARD) || !modelCatalog.containsKey(GenerationTier.PREMIUM)) {
            throw new IllegalArgumentException(
                    "El catálogo de modelos debe tener una entrada para STANDARD y otra para PREMIUM.");
        }
    }

    public String modelIdFor(GenerationTier tier) {
        return modelCatalog.get(tier);
    }

    /**
     * La tarifa configurada para modelId (ver {@link ModelPricing}). No hay
     * un default razonable acá: si falta la tarifa de un modelo, hay que
     * cargarla explícitamente (ver {@link KnownHiggsfieldPricing}) antes de
     * poder estimar costos contra él.
     */
    public ModelPricing pricingFor(String modelId) {
        ModelPricing pricing = pricingByModelId.get(modelId);
        if (pricing == null) {
            throw new IllegalArgumentException(
                    "No hay tarifa configurada para el modelo '" + modelId + "'. Agregala a "
                            + "HiggsfieldConfig.pricingByModelId antes de estimar costos contra ese modelo.");
        }
        return pricing;
    }
}
