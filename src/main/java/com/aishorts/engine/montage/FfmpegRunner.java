package com.aishorts.engine.montage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Invoca el binario ffmpeg como un proceso externo. No sabe nada de
 * escenas, historias ni Higgsfield — solo sabe ejecutar un comando ffmpeg
 * y fallar con contexto útil si algo sale mal. Todo lo que arma los
 * argumentos vive en {@link VideoMontageBuilder}.
 */
public final class FfmpegRunner {

    private final String ffmpegBinary;
    private final long timeoutSeconds;

    public FfmpegRunner() {
        this("ffmpeg", 300);
    }

    public FfmpegRunner(String ffmpegBinary, long timeoutSeconds) {
        this.ffmpegBinary = ffmpegBinary;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * Corre ffmpeg con los argumentos dados (sin incluir el nombre del
     * binario). Siempre agrega "-y" al principio porque este proceso corre
     * sin supervisión — nunca queremos que ffmpeg se quede esperando que
     * alguien confirme sobreescribir un archivo de salida.
     */
    public void run(List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(ffmpegBinary);
        command.add("-y");
        command.addAll(args);

        String commandLine = String.join(" ", command);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new FfmpegException(
                    "No se pudo iniciar ffmpeg (¿está instalado y en el PATH?). Comando: " + commandLine, e);
        }

        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            process.destroyForcibly();
            throw new FfmpegException("No se pudo leer la salida de ffmpeg. Comando: " + commandLine, e);
        }

        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new FfmpegException("Se interrumpió la espera de ffmpeg. Comando: " + commandLine, e);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new FfmpegException(
                    "ffmpeg no terminó dentro de " + timeoutSeconds + "s, se lo mató. Comando: " + commandLine
                            + "\nSalida hasta el corte:\n" + output);
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new FfmpegException(
                    "ffmpeg terminó con código " + exitCode + ". Comando: " + commandLine
                            + "\nSalida:\n" + output);
        }
    }
}
