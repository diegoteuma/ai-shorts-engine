package com.aishorts.engine.video;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La implementación real con subprocesos portables: la propia JVM corriendo
 * un .java suelto (modo "source file" del launcher, JDK 11+).
 */
class SystemProcessRunnerTest {

    private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();

    @TempDir
    Path tmp;

    private final SystemProcessRunner runner = new SystemProcessRunner();

    @Test
    void returnsTheExitCodeAndAppendsStdoutAndStderrToTheLog() throws IOException {
        Path src = source("Hola", """
                public class Hola {
                    public static void main(String[] a) {
                        System.out.println("salida estandar " + System.getenv("VARIABLE_DE_PRUEBA"));
                        System.err.println("salida de error");
                        System.exit(3);
                    }
                }
                """);
        Path log = tmp.resolve("logs").resolve("hola.log");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "linea previa\n");

        ProcessRunner.Result r = runner.run(List.of(JAVA, src.toString()), tmp, Map.of("VARIABLE_DE_PRUEBA", "valor"),
                Duration.ofSeconds(60), log);

        assertThat(r.exitCode()).isEqualTo(3);
        assertThat(r.timedOut()).isFalse();
        String text = Files.readString(log);
        assertThat(text).startsWith("linea previa\n");
        assertThat(text).contains("salida estandar valor").contains("salida de error");
    }

    @Test
    void theChildHasNoStandardInputSoAPromptDoesNotHang() throws IOException {
        Path src = source("Pregunta", """
                public class Pregunta {
                    public static void main(String[] a) throws Exception {
                        System.out.println("leido=" + System.in.read());
                    }
                }
                """);
        Path log = tmp.resolve("pregunta.log");

        ProcessRunner.Result r = runner.run(List.of(JAVA, src.toString()), tmp, Map.of(), Duration.ofSeconds(60), log);

        assertThat(r.timedOut()).isFalse();
        assertThat(Files.readString(log)).contains("leido=-1");
    }

    @Test
    void onTimeoutKillsTheProcessAndItsChildren() throws Exception {
        Path sleeper = source("Durmiente", """
                public class Durmiente {
                    public static void main(String[] a) throws Exception { Thread.sleep(120_000); }
                }
                """);
        Path pidFile = tmp.resolve("nieto.pid");
        Path parent = source("Abuelo", """
                import java.nio.file.*;
                public class Abuelo {
                    public static void main(String[] a) throws Exception {
                        Process hijo = new ProcessBuilder(a[0], a[1]).inheritIO().start();
                        Files.writeString(Path.of(a[2]), Long.toString(hijo.pid()));
                        Thread.sleep(120_000);
                    }
                }
                """);

        long t0 = System.nanoTime();
        ProcessRunner.Result r = runner.run(List.of(JAVA, parent.toString(), JAVA, sleeper.toString(), pidFile.toString()),
                tmp, Map.of(), Duration.ofSeconds(10), tmp.resolve("abuelo.log"));

        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(60));
        assertThat(r.timedOut()).isTrue();
        assertThat(r.exitCode()).isEqualTo(-1);
        assertThat(pidFile).as("el proceso padre no llegó a lanzar al hijo en 10 s").exists();
        long grandchild = Long.parseLong(Files.readString(pidFile).trim());
        assertThat(waitUntilDead(grandchild)).as("el hijo %d sigue vivo", grandchild).isTrue();
    }

    @Test
    void aCommandThatDoesNotExistIsAClearError() {
        assertThatThrownBy(() -> runner.run(List.of("comando-que-no-existe-xyz"), tmp, Map.of(), Duration.ofSeconds(5), tmp.resolve("x.log")))
                .isInstanceOf(FinalVideoException.class)
                .hasMessageContaining("comando-que-no-existe-xyz");
    }

    private Path source(String className, String code) throws IOException {
        Path file = tmp.resolve(className + ".java");
        Files.writeString(file, code);
        return file;
    }

    private static boolean waitUntilDead(long pid) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            if (handle.isEmpty() || !handle.get().isAlive()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }
}
