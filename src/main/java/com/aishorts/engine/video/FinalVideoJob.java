package com.aishorts.engine.video;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.List;

/**
 * El trabajo vigente de video final de una historia (uno por historia, sin
 * historial). Se guarda como JSON en RENDERS_DIR/{idHistoria}/job.json.
 * Inmutable: cada transición devuelve una copia.
 *
 * @param stage     etapa en curso (RUNNING) o la que falló (FAILED); null si no aplica
 * @param error     mensaje corto si FAILED
 * @param logTail   últimos ~4 KB del log de la etapa que falló
 * @param stages    duración y código de salida de cada etapa terminada (de make-video-result.json)
 * @param output    ruta del MP4 relativa a RENDERS_DIR, solo si COMPLETED
 */
public record FinalVideoJob(
        String id,
        String storyId,
        FinalVideoStatus status,
        String stage,
        boolean force,
        String createdAt,
        String startedAt,
        String finishedAt,
        String error,
        String logTail,
        List<Warning> warnings,
        List<StageTiming> stages,
        String output
) {

    public record Warning(String stage, String message) {
    }

    public record StageTiming(String name, Long ms, Integer exitCode, Boolean timedOut) {
    }

    public FinalVideoJob {
        warnings = warnings != null ? List.copyOf(warnings) : List.of();
        stages = stages != null ? List.copyOf(stages) : List.of();
    }

    public static FinalVideoJob pending(String id, String storyId, boolean force) {
        return new FinalVideoJob(id, storyId, FinalVideoStatus.PENDING, null, force, now(),
                null, null, null, null, List.of(), List.of(), null);
    }

    public FinalVideoJob running() {
        return new FinalVideoJob(id, storyId, FinalVideoStatus.RUNNING, null, force, createdAt, now(),
                null, null, null, warnings, stages, null);
    }

    /** Avance leído de make-video-result.json mientras corre (no cambia el estado). */
    public FinalVideoJob withProgress(String currentStage, List<StageTiming> doneStages, List<Warning> currentWarnings) {
        return new FinalVideoJob(id, storyId, status, currentStage, force, createdAt, startedAt,
                finishedAt, error, logTail, currentWarnings, doneStages, output);
    }

    public FinalVideoJob completed(String outputPath) {
        return new FinalVideoJob(id, storyId, FinalVideoStatus.COMPLETED, null, force, createdAt, startedAt,
                now(), null, null, warnings, stages, outputPath);
    }

    public FinalVideoJob failed(String failedStage, String message, String tail) {
        return new FinalVideoJob(id, storyId, FinalVideoStatus.FAILED, failedStage, force, createdAt, startedAt,
                now(), message, tail, warnings, stages, null);
    }

    @JsonIgnore // no es un campo: sin esto Jackson lo escribe como "active" en job.json
    public boolean isActive() {
        return status == FinalVideoStatus.PENDING || status == FinalVideoStatus.RUNNING;
    }

    private static String now() {
        return Instant.now().toString();
    }
}
