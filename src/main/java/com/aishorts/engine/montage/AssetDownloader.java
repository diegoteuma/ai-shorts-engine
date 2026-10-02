package com.aishorts.engine.montage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Baja a disco el clip generado que devuelve Higgsfield (una URL remota)
 * antes de pasarlo por ffmpeg, que necesita archivos locales, no URLs.
 * Consistente con lo que documenta Higgsfield: los assets generados hay
 * que bajarlos a almacenamiento propio, no asumir que la URL vive para
 * siempre.
 */
public final class AssetDownloader {

    private final HttpClient httpClient;

    public AssetDownloader() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build());
    }

    public AssetDownloader(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Descarga assetUrl a destination, sobreescribiendo si ya existe. Una
     * URL file:// no toca la red: copia el archivo local (los clips que ya
     * están en disco, cuando la URL de Higgsfield pudo haber expirado).
     */
    public Path download(String assetUrl, Path destination) {
        URI uri = URI.create(assetUrl);
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return copyLocalFile(Path.of(uri), destination);
        }
        try {
            Files.createDirectories(destination.getParent());
            HttpRequest request = HttpRequest.newBuilder(URI.create(assetUrl))
                    .timeout(Duration.ofSeconds(120))
                    .GET()
                    .build();
            HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(
                    destination,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE));
            if (response.statusCode() / 100 != 2) {
                throw new FfmpegException(
                        "Falló la descarga de '" + assetUrl + "': HTTP " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            throw new FfmpegException("Error de red descargando '" + assetUrl + "'.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FfmpegException("Se interrumpió la descarga de '" + assetUrl + "'.", e);
        }
    }

    private static Path copyLocalFile(Path source, Path destination) {
        if (!Files.isRegularFile(source)) {
            throw new FfmpegException("No existe el archivo local '" + source + "'.");
        }
        try {
            Files.createDirectories(destination.getParent());
            return Files.copy(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new FfmpegException("No se pudo copiar '" + source + "' a '" + destination + "'.", e);
        }
    }
}
