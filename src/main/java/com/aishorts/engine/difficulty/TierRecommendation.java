package com.aishorts.engine.difficulty;

import com.aishorts.engine.domain.GenerationTier;

/**
 * Sugerencia de tier para una escena, con el puntaje y el motivo en texto
 * plano para que se muestre junto al prompt en la primera puerta de revisión.
 * Es solo una sugerencia: quien decide es la persona al aprobar el prompt.
 */
public record TierRecommendation(GenerationTier tier, int score, String reason) {
}
