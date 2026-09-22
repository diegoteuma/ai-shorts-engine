package com.aishorts.engine.difficulty;

import com.aishorts.engine.domain.SceneRole;

public interface SceneDifficultyScorer {

    /**
     * Calcula un puntaje 0-100 combinando el peso narrativo del rol de la
     * escena con su complejidad visual, y sugiere STANDARD o PREMIUM.
     */
    TierRecommendation score(SceneRole role, DifficultyFactors factors);
}
