package com.aishorts.engine.domain;

import com.aishorts.engine.difficulty.TierRecommendation;
import com.aishorts.engine.higgsfield.EstimateResponse;

import java.time.Duration;

/**
 * Foto congelada de TODO el estado de una Scene, para poder guardarla y
 * reconstruirla exactamente igual en una ejecución futura (persistencia).
 *
 * A propósito esto no es la Scene misma: los métodos de Scene
 * (approvePrompt, recordCostEstimate, startGeneration, ...) están guardados
 * para hacer cumplir el orden de las dos puertas humanas durante una
 * ejecución en vivo — no tienen sentido al releer un estado que ya fue
 * válido en el pasado. Por eso {@link Scene#fromSnapshot(SceneSnapshot)}
 * reconstruye directo, sin pasar por esas transiciones.
 */
public record SceneSnapshot(
        String id,
        SceneRole role,
        int order,
        String narrationText,
        String visualPrompt,
        Duration targetDuration,
        TierRecommendation tierRecommendation,
        GenerationTier chosenTier,
        String chosenModelId,
        SceneApprovalStatus promptStatus,
        String promptRejectionNote,
        SceneCostStatus costStatus,
        EstimateResponse costEstimate,
        String costRejectionNote,
        String narrationAudioPath,
        GenerationStatus generationStatus,
        String higgsfieldRequestId,
        String higgsfieldStatusUrl,
        String generatedAssetUrl,
        String generationFailureReason
) {
}
