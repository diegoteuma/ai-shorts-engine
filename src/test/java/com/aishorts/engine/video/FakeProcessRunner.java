package com.aishorts.engine.video;

import com.aishorts.engine.clips.Mp4Fixtures;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Simula {@code node make-video.mjs} sin Node: escribe en la carpeta de
 * trabajo (--work) lo mismo que el script real (make-video-result.json,
 * logs/{etapa}.log y final.mp4) según el modo. {@code node --version} siempre
 * responde bien.
 */
public final class FakeProcessRunner implements ProcessRunner {

    public enum Mode { SUCCESS, FAIL, TIMEOUT, BLOCK }

    private static final List<String> STAGES = List.of("prepare", "transcribe", "generate", "lint", "check", "render", "validate");

    public volatile Mode mode = Mode.SUCCESS;
    public volatile String failStage = "render";
    public volatile byte[] video = Mp4Fixtures.valid();
    public volatile CountDownLatch started = new CountDownLatch(1);
    public volatile CountDownLatch release = new CountDownLatch(1);
    public final List<List<String>> calls = Collections.synchronizedList(new ArrayList<>());

    public void reset() {
        mode = Mode.SUCCESS;
        failStage = "render";
        video = Mp4Fixtures.valid();
        started = new CountDownLatch(1);
        release = new CountDownLatch(1);
        calls.clear();
    }

    /** Llamadas a make-video (sin contar los node --version). */
    public List<List<String>> makeVideoCalls() {
        synchronized (calls) {
            return calls.stream().filter(c -> c.size() > 1 && c.get(1).endsWith("make-video.mjs")).toList();
        }
    }

    @Override
    public Result run(List<String> command, Path workingDir, Map<String, String> extraEnv, Duration timeout, Path logFile) {
        calls.add(List.copyOf(command));
        if (command.contains("--version")) {
            return new Result(0, false);
        }
        Path work = Path.of(command.get(command.indexOf("--work") + 1));
        boolean skipCheck = command.contains("--skip-check");
        try {
            Files.createDirectories(work.resolve("logs"));
            Files.writeString(logFile, "salida simulada de make-video\n", StandardCharsets.UTF_8);
            switch (mode) {
                case SUCCESS -> {
                    return succeed(work, skipCheck);
                }
                case FAIL -> {
                    int upTo = STAGES.indexOf(failStage);
                    Files.writeString(work.resolve("logs").resolve(failStage + ".log"),
                            "x".repeat(6000) + "\ndetalle del fallo en " + failStage + "\n", StandardCharsets.UTF_8);
                    writeResult(work, "FAILED", failStage, failStage + " encontró errores (código 1): simulado", upTo, 1, skipCheck);
                    return new Result(1, false);
                }
                case TIMEOUT -> {
                    writeResult(work, "RUNNING", "render", null, STAGES.indexOf("render"), null, skipCheck);
                    return new Result(-1, true);
                }
                case BLOCK -> {
                    writeResult(work, "RUNNING", "render", null, STAGES.indexOf("render"), null, skipCheck);
                    started.countDown();
                    if (!release.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("el test nunca liberó el render simulado");
                    }
                    return succeed(work, skipCheck);
                }
                default -> throw new IllegalStateException(mode.name());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, true);
        }
    }

    private Result succeed(Path work, boolean skipCheck) throws IOException {
        Files.write(work.resolve("final.mp4"), video);
        writeResult(work, "COMPLETED", null, null, STAGES.size(), null, skipCheck);
        return new Result(0, false);
    }

    /**
     * Como make-video: `stages` trae las terminadas; si hay {@code failedExit}, la última es la que falló.
     * Con --skip-check, check queda como {@code {"ms":0,"exitCode":null,"skipped":true}}.
     */
    private static void writeResult(Path work, String status, String stage, String error, int stagesDone, Integer failedExit,
                                    boolean skipCheck) throws IOException {
        StringBuilder stages = new StringBuilder();
        for (int i = 0; i < stagesDone; i++) {
            stages.append(i > 0 ? "," : "").append("{\"name\":\"").append(STAGES.get(i)).append("\"");
            if (skipCheck && STAGES.get(i).equals("check")) {
                stages.append(",\"ms\":0,\"exitCode\":null,\"skipped\":true}");
            } else {
                stages.append(",\"ms\":").append(10 + i).append(",\"exitCode\":0}");
            }
        }
        if (failedExit != null) {
            stages.append(stagesDone > 0 ? "," : "").append("{\"name\":\"").append(stage).append("\",\"ms\":5,\"exitCode\":").append(failedExit).append("}");
        }
        String json = "{\"status\":\"" + status + "\",\"stage\":" + (stage == null ? "null" : "\"" + stage + "\"")
                + ",\"error\":" + (error == null ? "null" : "\"" + error + "\"")
                + ",\"stages\":[" + stages + "]"
                + ",\"warnings\":[{\"stage\":\"lint\",\"message\":\"composition_file_too_large: aviso simulado\"}]"
                + ",\"output\":" + ("COMPLETED".equals(status) ? "\"final.mp4\"" : "null") + "}";
        Files.writeString(work.resolve("make-video-result.json"), json, StandardCharsets.UTF_8);
    }
}
