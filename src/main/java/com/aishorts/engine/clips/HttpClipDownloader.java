package com.aishorts.engine.clips;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Descarga por HTTP(S) con dos límites duros:
 * <ul>
 *   <li><b>Tamaño</b>: se corta al superar {@code maxBytes} contando los bytes
 *       realmente leídos (no se confía en Content-Length, que puede faltar o
 *       mentir).</li>
 *   <li><b>Tiempo total</b> ({@code totalTimeout}) desde que se envía el pedido
 *       hasta el último byte. {@code HttpRequest.timeout} solo cubre hasta los
 *       headers; para el cuerpo, un vigilante cierra el stream al vencer el
 *       plazo, lo que destraba un {@code read()} bloqueado.</li>
 * </ul>
 * Usa HTTP/1.1 por precaución (en este proyecto HTTP/2 dio problemas detrás de
 * inspección TLS corporativa con la API de Claude; no se probó si afecta a los
 * CDN de Higgsfield). Solo acepta esquemas http y https.
 */
public final class HttpClipDownloader implements ClipDownloader {

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "clip-download-watchdog");
        t.setDaemon(true);
        return t;
    });

    private final HttpClient client;
    private final long maxBytes;
    private final Duration totalTimeout;

    public HttpClipDownloader(long maxBytes, Duration connectTimeout, Duration totalTimeout) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes debe ser positivo.");
        }
        this.maxBytes = maxBytes;
        this.totalTimeout = totalTimeout;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(connectTimeout)
                .build();
    }

    @Override
    public void download(String url, Path destination) {
        URI uri = parse(url);
        long deadlineNanos = System.nanoTime() + totalTimeout.toNanos();
        AtomicBoolean timedOut = new AtomicBoolean(false);
        InputStream body = null;
        ScheduledFuture<?> guard = null;
        boolean ok = false;
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).GET().timeout(totalTimeout).build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            body = response.body();

            long remainingMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
            final InputStream guarded = body;
            guard = WATCHDOG.schedule(() -> {
                timedOut.set(true);
                closeQuietly(guarded);
            }, remainingMs, TimeUnit.MILLISECONDS);

            if (response.statusCode() != 200) {
                throw new ClipException("El servidor respondió HTTP " + response.statusCode() + " al pedir el clip.");
            }
            OptionalLong declared = response.headers().firstValueAsLong("Content-Length");
            if (declared.isPresent() && declared.getAsLong() > maxBytes) {
                throw new ClipException("El clip declara " + declared.getAsLong()
                        + " bytes, más que el tope de " + maxBytes + ".");
            }

            long total = copyCapped(body, destination);
            if (timedOut.get()) {
                // Cerrar el stream desde el vigilante puede aparecer como EOF limpio: no es una descarga completa.
                throw timeout();
            }
            if (declared.isPresent() && declared.getAsLong() != total) {
                throw new ClipException("Descarga incompleta: se esperaban " + declared.getAsLong()
                        + " bytes y llegaron " + total + ".");
            }
            if (total == 0) {
                throw new ClipException("El servidor devolvió un cuerpo vacío.");
            }
            ok = true;
        } catch (ClipException e) {
            if (timedOut.get()) {
                throw timeout();
            }
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClipException("Descarga interrumpida.", e);
        } catch (IOException e) {
            if (timedOut.get() || isTimeout(e)) {
                throw timeout();
            }
            throw new ClipException("Falló la descarga: " + describe(e), e);
        } finally {
            if (guard != null) {
                guard.cancel(false);
            }
            closeQuietly(body);
            if (!ok) {
                try {
                    Files.deleteIfExists(destination);
                } catch (IOException ignored) {
                    // se limpia al reintentar; no enmascarar el error original.
                }
            }
        }
    }

    private long copyCapped(InputStream in, Path destination) throws IOException {
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (OutputStream out = Files.newOutputStream(destination,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > maxBytes) {
                    throw new ClipException("El clip supera el tope de " + maxBytes + " bytes; descarga cortada.");
                }
                out.write(buffer, 0, n);
            }
        }
        return total;
    }

    private ClipException timeout() {
        return new ClipException("Tiempo agotado: la descarga superó " + totalTimeout.toSeconds() + " s.");
    }

    private static boolean isTimeout(IOException e) {
        return e instanceof java.net.http.HttpTimeoutException
                || e instanceof InterruptedIOException;
    }

    private static URI parse(String url) {
        if (url == null || url.isBlank()) {
            throw new ClipException("La escena no tiene URL de clip.");
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new ClipException("Esquema de URL no permitido (solo http/https): " + scheme);
            }
            if (uri.getHost() == null) {
                throw new ClipException("La URL del clip no tiene host.");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new ClipException("URL de clip inválida.", e);
        }
    }

    private static String describe(IOException e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (IOException ignored) {
            // nada que hacer.
        }
    }
}
