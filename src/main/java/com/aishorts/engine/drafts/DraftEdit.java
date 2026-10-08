package com.aishorts.engine.drafts;

import java.util.List;

/**
 * Ediciones manuales a un borrador PENDING_REVIEW, antes de decidir: título
 * nuevo y textos de escena (narración y prompt visual). Todo opcional, pero
 * al menos uno tiene que venir.
 */
public record DraftEdit(
        String title,
        List<DraftDecision.SceneTextEdit> narrationEdits,
        List<DraftDecision.SceneTextEdit> promptEdits
) {
    public DraftEdit {
        narrationEdits = narrationEdits != null ? List.copyOf(narrationEdits) : List.of();
        promptEdits = promptEdits != null ? List.copyOf(promptEdits) : List.of();
    }

    public boolean isEmpty() {
        return title == null && narrationEdits.isEmpty() && promptEdits.isEmpty();
    }
}
