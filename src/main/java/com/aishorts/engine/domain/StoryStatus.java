package com.aishorts.engine.domain;

/**
 * Estado agregado de la historia completa. El sistema avanza por lote
 * (todas las escenas del Short juntas), nunca escena por escena:
 *
 *   DRAFT
 *     -> PROMPTS_PENDING_REVIEW   (prompts propuestos, esperando tu aprobación)
 *     -> PROMPTS_APPROVED         (las 6 escenas tienen prompt decidido; puede haber rechazos)
 *     -> COSTS_PENDING_REVIEW     (costos consultados a la API, esperando tu aprobación)
 *     -> COSTS_APPROVED           (las escenas con prompt aprobado tienen costo decidido)
 *     -> GENERATING               (se dispararon las generaciones de las escenas con costo aprobado)
 *     -> GENERATED                (todas las escenas a generar están completas)
 *     -> PUBLISHED
 *
 * Nunca hay una transición automática hacia GENERATING: esa transición solo
 * ocurre cuando se invoca explícitamente el paso de generación después de
 * que la persona aprobó el lote de costos.
 */
public enum StoryStatus {
    DRAFT,
    PROMPTS_PENDING_REVIEW,
    PROMPTS_APPROVED,
    COSTS_PENDING_REVIEW,
    COSTS_APPROVED,
    GENERATING,
    GENERATED,
    PUBLISHED
}
