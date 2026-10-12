package com.aishorts.engine.video;

import java.util.Map;

/**
 * Error del video final con su status HTTP: 404 (historia o trabajo
 * inexistente), 409 (estado: render en curso, video existente, escenas sin
 * terminar), 422 (datos: ids inválidos, clip que no pasa Mp4Probe). Lleva el
 * problema de cada escena afectada y, si aplica, el id del trabajo en curso.
 */
public final class FinalVideoException extends RuntimeException {

    private final int status;
    private final Map<String, String> sceneProblems;
    private final String jobId;

    public FinalVideoException(int status, String message) {
        this(status, message, Map.of(), null);
    }

    public FinalVideoException(int status, String message, Map<String, String> sceneProblems, String jobId) {
        super(message);
        this.status = status;
        this.sceneProblems = Map.copyOf(sceneProblems);
        this.jobId = jobId;
    }

    public int status() {
        return status;
    }

    public Map<String, String> sceneProblems() {
        return sceneProblems;
    }

    public String jobId() {
        return jobId;
    }
}
