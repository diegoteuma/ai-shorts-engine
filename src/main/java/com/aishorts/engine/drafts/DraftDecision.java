package com.aishorts.engine.drafts;

import com.aishorts.engine.approval.Decision;

import java.util.List;
import java.util.Objects;

/** Tu decisión en la PUERTA 0 sobre un borrador, con las ediciones opcionales de narración y prompt visual. */
public record DraftDecision(
        Decision decision,
        String note,
        List<SceneTextEdit> narrationEdits,
        List<SceneTextEdit> promptEdits,
        boolean acknowledgeUnverified
) {
    public DraftDecision {
        Objects.requireNonNull(decision, "decision");
        narrationEdits = narrationEdits != null ? List.copyOf(narrationEdits) : List.of();
        promptEdits = promptEdits != null ? List.copyOf(promptEdits) : List.of();
    }

    /** Texto nuevo para una escena (narrationText o visualPrompt, según la lista en la que venga). */
    public record SceneTextEdit(String sceneId, String text) {
    }
}
