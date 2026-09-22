package com.aishorts.engine.higgsfield;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Respuesta del endpoint estimate. `raw` guarda el JSON parseado completo
 * por si el modelo devuelve campos adicionales (ej. desglose por segundo de
 * video) que todavía no están mapeados a un campo propio.
 */
public record EstimateResponse(BigDecimal cost, String currency, String modelId, Map<String, Object> raw) {
}
