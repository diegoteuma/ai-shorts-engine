package com.aishorts.engine.drafts;

import java.util.List;

/**
 * Error del generador de historias que se traduce directo a una respuesta
 * HTTP con ese status (400, 404, 409, 422, 502), con las violaciones
 * adjuntas cuando el motivo es un borrador no aprobable.
 */
public class DraftException extends RuntimeException {
    private final int status;
    private final List<Violation> violations;

    public DraftException(int status, String message) {
        this(status, message, List.of(), null);
    }

    public DraftException(int status, String message, Throwable cause) {
        this(status, message, List.of(), cause);
    }

    public DraftException(int status, String message, List<Violation> violations) {
        this(status, message, violations, null);
    }

    private DraftException(int status, String message, List<Violation> violations, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.violations = violations != null ? List.copyOf(violations) : List.of();
    }

    public int status() {
        return status;
    }

    public List<Violation> violations() {
        return violations;
    }
}
