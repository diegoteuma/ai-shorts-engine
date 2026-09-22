package com.aishorts.engine.script;

import com.aishorts.engine.duration.DurationBudget;

import java.util.List;
import java.util.Objects;

/**
 * Lo que vos ya decidiste antes de que exista un borrador de escenas: tema,
 * título (el gancho + el nombre que se revela después, como en Tunguska),
 * los hechos núcleo con fuentes, las restricciones editoriales del formato
 * (ej. "no inventar cifras de víctimas ni daños"), y el presupuesto de
 * duración total del Short.
 *
 * Esto es el input del servicio de guionado — nunca lo arma el LLM por su
 * cuenta.
 */
public record StoryBrief(
        String topic,
        String title,
        List<String> coreFacts,
        List<String> constraints,
        DurationBudget durationBudget
) {
    public StoryBrief {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(coreFacts, "coreFacts");
        Objects.requireNonNull(constraints, "constraints");
        Objects.requireNonNull(durationBudget, "durationBudget");
        if (coreFacts.isEmpty()) {
            throw new IllegalArgumentException("coreFacts no puede estar vacío: todo REAL necesita al menos un hecho fuente.");
        }
    }
}
