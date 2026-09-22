package com.aishorts.engine.approval;

import com.aishorts.engine.domain.GenerationTier;

/**
 * Una decisión tuya sobre UNA escena, dentro del lote de la historia
 * completa. Los tres campos de revisión son independientes y opcionales —
 * dejalos null para aceptar tal cual lo que se propuso.
 *
 * revisedNarrationText importa además de para el texto en sí: dispara el
 * recálculo de la duración estimada de la escena (y por lo tanto de la
 * suma total de la historia) en StoryApprovalService.applyPromptDecisions.
 */
public record PromptDecision(
        String sceneId,
        Decision decision,
        String revisedNarrationText,
        String revisedVisualPrompt,
        GenerationTier tierOverride,
        String note
) {
    public static PromptDecision approve(String sceneId) {
        return new PromptDecision(sceneId, Decision.APPROVE, null, null, null, null);
    }

    public static PromptDecision approveWithNarrationRevision(String sceneId, String revisedNarrationText) {
        return new PromptDecision(sceneId, Decision.APPROVE, revisedNarrationText, null, null, null);
    }

    public static PromptDecision approveWithVisualRevision(String sceneId, String revisedVisualPrompt) {
        return new PromptDecision(sceneId, Decision.APPROVE, null, revisedVisualPrompt, null, null);
    }

    public static PromptDecision approveWithTierOverride(String sceneId, GenerationTier tierOverride) {
        return new PromptDecision(sceneId, Decision.APPROVE, null, null, tierOverride, null);
    }

    public static PromptDecision reject(String sceneId, String note) {
        return new PromptDecision(sceneId, Decision.REJECT, null, null, null, note);
    }
}
