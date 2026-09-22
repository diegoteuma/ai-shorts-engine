package com.aishorts.engine.higgsfield;

import java.util.Map;

/**
 * modelId: el model_id de Higgsfield (ej. "higgsfield-ai/soul/v2/standard").
 * parameters: los mismos parámetros que se usarían para generar (prompt,
 * aspect_ratio, duración, resolución, etc.) — el endpoint estimate exige los
 * parámetros exactos de la generación real para que el número sea confiable.
 */
public record EstimateRequest(String modelId, Map<String, Object> parameters) {
}
