package com.aishorts.engine.clips;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contra un servidor HTTP local real (com.sun.net.httpserver, parte del JDK):
 * sin red externa. Los casos de tiempo usan plazos de ~1 s y verifican que la
 * llamada vuelva mucho antes de que el servidor termine de colgarse (8 s).
 */
class HttpClipDownloaderTest {

    private static final long CAP = 10_000;

    @TempDir
    Path dir;

    private HttpServer server;
    private ExecutorService pool;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private String serve(String path, HttpHandler handler) throws IOException {
        if (server == null) {
            pool = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                return t;
            });
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(pool);
            server.start();
        }
        server.createContext(path, handler);
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i * 31);
        }
        return b;
    }

    private HttpClipDownloader downloader(long cap, long totalTimeoutMillis) {
        return new HttpClipDownloader(cap, Duration.ofSeconds(5), Duration.ofMillis(totalTimeoutMillis));
    }

    @Test
    void downloadsTheBodyToTheDestination() throws IOException {
        byte[] body = bytes(7_000);
        String url = serve("/ok", ex -> {
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        Path dest = dir.resolve("ok.part");

        downloader(CAP, 5_000).download(url, dest);

        assertThat(Arrays.equals(Files.readAllBytes(dest), body)).isTrue();
    }

    @Test
    void rejectsNon200AndLeavesNoFile() throws IOException {
        String url = serve("/missing", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        Path dest = dir.resolve("missing.part");

        assertThatThrownBy(() -> downloader(CAP, 5_000).download(url, dest))
                .isInstanceOf(ClipException.class)
                .hasMessageContaining("HTTP 404");
        assertThat(Files.exists(dest)).isFalse();
    }

    @Test
    void rejectsADeclaredLengthAboveTheCapAndLeavesNoFile() throws IOException {
        byte[] body = bytes(50_000);
        String url = serve("/big", ex -> {
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            } catch (IOException ignored) {
                // el cliente corta apenas ve el Content-Length.
            }
        });
        Path dest = dir.resolve("big.part");

        assertThatThrownBy(() -> downloader(CAP, 5_000).download(url, dest))
                .isInstanceOf(ClipException.class)
                .hasMessageContaining("tope");
        assertThat(Files.exists(dest)).isFalse();
    }

    @Test
    void cutsAChunkedBodyAtTheCapWithoutTrustingContentLength() throws IOException {
        // Sin Content-Length (chunked): el tope se aplica contando los bytes leídos.
        byte[] chunk = bytes(4_000);
        String url = serve("/chunked-big", ex -> {
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                for (int i = 0; i < 100; i++) {
                    os.write(chunk);
                    os.flush();
                }
            } catch (IOException ignored) {
                // el cliente corta al superar el tope.
            }
        });
        Path dest = dir.resolve("chunked.part");

        assertThatThrownBy(() -> downloader(CAP, 5_000).download(url, dest))
                .isInstanceOf(ClipException.class)
                .hasMessageContaining("tope");
        assertThat(Files.exists(dest)).isFalse();
    }

    @Test
    void timesOutWhenTheBodyStallsMidway() throws IOException {
        String url = serve("/stall", ex -> {
            ex.sendResponseHeaders(200, 0);
            OutputStream os = ex.getResponseBody();
            os.write(bytes(1_000));
            os.flush();
            try {
                Thread.sleep(8_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try {
                os.close();
            } catch (IOException ignored) {
                // el cliente ya se fue.
            }
        });
        Path dest = dir.resolve("stall.part");

        long started = System.nanoTime();
        assertThatThrownBy(() -> downloader(CAP, 1_000).download(url, dest))
                .isInstanceOf(ClipException.class)
                .hasMessageContaining("Tiempo agotado");
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(elapsedMs < 5_000).isTrue();
        assertThat(Files.exists(dest)).isFalse();
    }

    @Test
    void timesOutWhenTheHeadersNeverArrive() throws IOException {
        String url = serve("/silent", ex -> {
            try {
                Thread.sleep(8_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ex.close();
        });
        Path dest = dir.resolve("silent.part");

        long started = System.nanoTime();
        assertThatThrownBy(() -> downloader(CAP, 1_000).download(url, dest))
                .isInstanceOf(ClipException.class)
                .hasMessageContaining("Tiempo agotado");
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(elapsedMs < 5_000).isTrue();
    }

    @Test
    void followsRedirects() throws IOException {
        byte[] body = bytes(2_000);
        String target = serve("/final", ex -> {
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        String url = serve("/moved", ex -> {
            ex.getResponseHeaders().add("Location", target);
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        Path dest = dir.resolve("redirect.part");

        downloader(CAP, 5_000).download(url, dest);

        assertThat(Arrays.equals(Files.readAllBytes(dest), body)).isTrue();
    }

    @Test
    void rejectsABodyShorterThanContentLength() throws IOException {
        String url = serve("/short", ex -> {
            ex.sendResponseHeaders(200, 9_000);
            try {
                OutputStream os = ex.getResponseBody();
                os.write(bytes(3_000));
                os.flush();
                ex.close();
            } catch (IOException ignored) {
                // el servidor se queja de que faltan bytes; justo lo que se quiere simular.
            }
        });
        Path dest = dir.resolve("short.part");

        assertThatThrownBy(() -> downloader(CAP, 5_000).download(url, dest))
                .isInstanceOf(ClipException.class);
        assertThat(Files.exists(dest)).isFalse();
    }

    @Test
    void rejectsAnEmptyBody() throws IOException {
        String url = serve("/empty", ex -> {
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        Path dest = dir.resolve("empty.part");

        assertThatThrownBy(() -> downloader(CAP, 5_000).download(url, dest))
                .isInstanceOf(ClipException.class)
                .hasMessageContaining("vacío");
        assertThat(Files.exists(dest)).isFalse();
    }

    @Test
    void rejectsSchemesOtherThanHttpAndHttps() {
        Path dest = dir.resolve("scheme.part");
        HttpClipDownloader d = downloader(CAP, 5_000);

        assertThatThrownBy(() -> d.download("file:///etc/passwd", dest))
                .isInstanceOf(ClipException.class)
                .hasMessageContaining("Esquema");
        assertThatThrownBy(() -> d.download("ftp://example.com/a.mp4", dest))
                .isInstanceOf(ClipException.class)
                .hasMessageContaining("Esquema");
    }

    @Test
    void rejectsBlankAndMalformedUrls() {
        Path dest = dir.resolve("bad.part");
        HttpClipDownloader d = downloader(CAP, 5_000);

        assertThatThrownBy(() -> d.download("  ", dest)).isInstanceOf(ClipException.class);
        assertThatThrownBy(() -> d.download(null, dest)).isInstanceOf(ClipException.class);
        assertThatThrownBy(() -> d.download("http://", dest)).isInstanceOf(ClipException.class);
    }
}
