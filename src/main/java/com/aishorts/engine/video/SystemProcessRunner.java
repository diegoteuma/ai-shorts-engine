package com.aishorts.engine.video;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@link ProcessRunner} real sobre {@link ProcessBuilder}.
 *
 * Al vencer el tiempo (o si se interrumpe el hilo, p. ej. al apagar la app)
 * mata el árbol completo: primero los descendientes y después el proceso,
 * porque una vez muerto el padre sus hijos ya no aparecen en
 * {@link Process#descendants()} (quedarían huérfanos, como los Chrome de
 * {@code hyperframes render}). Se repite unas vueltas por si alguno alcanzó
 * a lanzar otro mientras tanto.
 */
public final class SystemProcessRunner implements ProcessRunner {

    private static final int KILL_ROUNDS = 5;

    @Override
    public Result run(List<String> command, Path workingDir, Map<String, String> extraEnv, Duration timeout, Path logFile) {
        Process process;
        try {
            Files.createDirectories(logFile.toAbsolutePath().getParent());
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(workingDir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            builder.environment().putAll(extraEnv);
            process = builder.start();
        } catch (IOException e) {
            throw new FinalVideoException(500, "No se pudo lanzar '" + command.get(0) + "': " + e.getMessage());
        }
        try {
            process.getOutputStream().close(); // sin entrada estándar: un prompt interactivo lee EOF en vez de colgarse
        } catch (IOException ignored) {
            // el proceso ya terminó; no importa
        }
        try {
            if (process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return new Result(process.exitValue(), false);
            }
            killTree(process);
            return new Result(-1, true);
        } catch (InterruptedException e) {
            killTree(process);
            Thread.currentThread().interrupt();
            throw new FinalVideoException(500, "Interrumpido mientras corría '" + command.get(0) + "'; se mató el proceso.");
        }
    }

    static void killTree(Process process) {
        for (int round = 0; round < KILL_ROUNDS; round++) {
            List<ProcessHandle> descendants = process.descendants().toList();
            if (descendants.isEmpty()) {
                break;
            }
            descendants.forEach(ProcessHandle::destroyForcibly);
        }
        process.destroyForcibly();
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
