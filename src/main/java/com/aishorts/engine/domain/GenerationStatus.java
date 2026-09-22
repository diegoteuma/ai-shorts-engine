package com.aishorts.engine.domain;

/**
 * Estado de la generación real en Higgsfield. Solo puede avanzar de
 * NOT_STARTED a QUEUED cuando el costo de la escena ya fue APPROVED —
 * eso se hace cumplir en {@link Scene#startGeneration()}, no por convención.
 */
public enum GenerationStatus {
    NOT_STARTED,
    QUEUED,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}
