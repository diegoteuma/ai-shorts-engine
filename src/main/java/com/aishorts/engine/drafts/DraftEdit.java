package com.aishorts.engine.drafts;

import java.util.List;

/**
 * Ediciones manuales a un borrador PENDING_REVIEW, antes de decidir: título
 * nuevo, texto en pantalla del gancho (hookText) y textos de escena
 * (narración, prompt visual y rótulo de lugar; un rótulo vacío lo quita).
 * Todo opcional, pero al menos uno tiene que venir.
 */
public record DraftEdit(
        String title,
        String hookText,
        List<DraftDecision.SceneTextEdit> narrationEdits,
        List<DraftDecision.SceneTextEdit> promptEdits,
        List<DraftDecision.SceneTextEdit> locationEdits
) {
    public DraftEdit {
        narrationEdits = narrationEdits != null ? List.copyOf(narrationEdits) : List.of();
        promptEdits = promptEdits != null ? List.copyOf(promptEdits) : List.of();
        locationEdits = locationEdits != null ? List.copyOf(locationEdits) : List.of();
    }

    public boolean isEmpty() {
        return title == null && hookText == null && narrationEdits.isEmpty() && promptEdits.isEmpty()
                && locationEdits.isEmpty();
    }
}
