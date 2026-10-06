package com.aishorts.engine.drafts;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Una regla del borrador que no se cumple, calculada siempre por el
 * servidor. sceneId/claimId son opcionales: indican dónde está el problema
 * cuando aplica a una escena o afirmación concreta.
 */
public record Violation(String code, Severity severity, String sceneId, String claimId, String message) {

    public static Violation hard(String code, String message) {
        return new Violation(code, Severity.HARD, null, null, message);
    }

    public static Violation soft(String code, String message) {
        return new Violation(code, Severity.SOFT, null, null, message);
    }

    public static Violation hardScene(String code, String sceneId, String message) {
        return new Violation(code, Severity.HARD, sceneId, null, message);
    }

    public static Violation softScene(String code, String sceneId, String message) {
        return new Violation(code, Severity.SOFT, sceneId, null, message);
    }

    public static Violation hardClaim(String code, String claimId, String message) {
        return new Violation(code, Severity.HARD, null, claimId, message);
    }

    public static Violation softClaim(String code, String claimId, String message) {
        return new Violation(code, Severity.SOFT, null, claimId, message);
    }

    @JsonIgnore
    public boolean isHard() {
        return severity == Severity.HARD;
    }
}
