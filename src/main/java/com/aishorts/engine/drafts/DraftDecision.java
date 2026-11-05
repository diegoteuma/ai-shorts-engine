package com.aishorts.engine.drafts;

import com.aishorts.engine.approval.Decision;

import java.util.List;
import java.util.Objects;

/**
 * Tu decisión en la PUERTA 0 sobre un borrador, con las ediciones opcionales
 * de narración, prompt visual, texto en pantalla del gancho (hookText; null
 * = no se cambia) y rótulo de lugar por escena (locationEdits; texto vacío
 * = se quita el rótulo).
 */
public record DraftDecision(
        Decision decision,
        String note,
        List<SceneTextEdit> narrationEdits,
        List<SceneTextEdit> promptEdits,
        boolean acknowledgeUnverified,
        String hookText,
        List<SceneTextEdit> locationEdits
) {
    public DraftDecision {
        Objects.requireNonNull(decision, "decision");
        narrationEdits = narrationEdits != null ? List.copyOf(narrationEdits) : List.of();
        promptEdits = promptEdits != null ? List.copyOf(promptEdits) : List.of();
        locationEdits = locationEdits != null ? List.copyOf(locationEdits) : List.of();
    }

    /** Texto nuevo para una escena (narrationText, visualPrompt o locationLabel, según la lista en la que venga). */
    public record SceneTextEdit(String sceneId, String text) {
    }
}
