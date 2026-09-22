package com.aishorts.engine.difficulty;

import com.aishorts.engine.domain.GenerationTier;
import com.aishorts.engine.domain.SceneRole;

/**
 * Implementación por defecto: peso narrativo del rol (0-30, ver
 * {@link SceneRole#narrativeWeight()}) + complejidad visual escalada a 0-70
 * (los 4 factores de {@link DifficultyFactors}, máximo 40, escalados a 70).
 *
 * Puntaje total 0-100. Igual o por encima del umbral -> PREMIUM.
 *
 * El umbral es deliberadamente conservador (60): en un piloto que todavía
 * tiene que validar costo real, conviene que el sistema se incline por
 * STANDARD salvo que la escena lo justifique con claridad, y que sea la
 * persona quien decida subir de tier en casos límite al aprobar el prompt.
 */
public final class DefaultSceneDifficultyScorer implements SceneDifficultyScorer {

    private static final int PREMIUM_THRESHOLD = 60;
    private static final double VISUAL_SCALE = 70.0 / 40.0;

    @Override
    public TierRecommendation score(SceneRole role, DifficultyFactors factors) {
        int narrativeWeight = role.narrativeWeight();
        int visualScore = (int) Math.round(factors.rawSum() * VISUAL_SCALE);
        int total = Math.min(100, narrativeWeight + visualScore);

        GenerationTier tier = total >= PREMIUM_THRESHOLD ? GenerationTier.PREMIUM : GenerationTier.STANDARD;

        String reason = "peso narrativo del rol " + role + "=" + narrativeWeight
                + ", complejidad visual=" + visualScore + " (de factores "
                + factors.movementComplexity() + "/" + factors.elementCount() + "/"
                + factors.physicalRealism() + "/" + factors.cameraDynamism()
                + "), total=" + total + ", umbral premium=" + PREMIUM_THRESHOLD;

        return new TierRecommendation(tier, total, reason);
    }
}
