package com.aishorts.engine.higgsfield;

/**
 * Puerto hacia la API de Higgsfield. La implementación real
 * ({@link HiggsfieldRestClient}) usa HTTP; en tests o demos se puede usar un
 * fake que nunca llega a la red.
 *
 * Importante: esta interfaz no tiene ningún método que "genere si el costo
 * es aceptable" ni nada equivalente. Estimar y generar son dos llamadas
 * separadas, y quien las conecta (la capa de aprobación) solo invoca
 * submitGeneration después de que la persona aprobó el costo explícitamente.
 */
public interface HiggsfieldClient {

    EstimateResponse estimateCost(EstimateRequest request) throws HiggsfieldException;

    GenerationResponse submitGeneration(GenerationRequest request) throws HiggsfieldException;

    /** statusUrl: pasar exactamente el valor de {@link GenerationResponse#statusUrl()}. */
    GenerationStatusResponse pollStatus(String statusUrl) throws HiggsfieldException;
}
