package com.aishorts.engine.domain;

/**
 * Nivel de generador visual a usar para una escena.
 * La sugerencia la calcula {@link com.aishorts.engine.difficulty.SceneDifficultyScorer},
 * pero el valor final siempre queda sujeto a lo que la persona apruebe en la
 * primera puerta de revisión (prompt), nunca se decide solo.
 */
public enum GenerationTier {
    STANDARD,
    PREMIUM
}
