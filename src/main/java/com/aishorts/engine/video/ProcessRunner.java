package com.aishorts.engine.video;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Puerto para lanzar un proceso externo (hoy, solo {@code node make-video.mjs}).
 * Existe para poder probar {@link FinalVideoService} sin Node real
 * (FakeProcessRunner en los tests).
 *
 * Contrato: los argumentos van como lista (nunca por shell); stdout y stderr
 * se agregan a {@code logFile} (no se acumulan en memoria); sin entrada
 * estándar; al vencer {@code timeout} se mata el proceso con todos sus
 * descendientes y se devuelve {@code timedOut = true}.
 */
public interface ProcessRunner {

    Result run(List<String> command, Path workingDir, Map<String, String> extraEnv, Duration timeout, Path logFile);

    /** {@code exitCode} es -1 cuando venció el tiempo. */
    record Result(int exitCode, boolean timedOut) {
    }
}
