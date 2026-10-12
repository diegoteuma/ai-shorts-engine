package com.aishorts.engine.clips;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ClipDownloader para los tests de API: en vez de ir a la red escribe un MP4
 * mínimo válido (720x1280). Puede hacer fallar las próximas N descargas para
 * probar el camino de error y el reintento.
 */
public final class FakeClipDownloader implements ClipDownloader {

    private final AtomicInteger failuresLeft = new AtomicInteger();
    private final List<String> calls = new CopyOnWriteArrayList<>();

    public void failNextCalls(int count) {
        failuresLeft.set(count);
    }

    public List<String> calls() {
        return List.copyOf(calls);
    }

    @Override
    public void download(String url, Path destination) {
        calls.add(url);
        if (failuresLeft.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throw new ClipException("Descarga simulada fallida para " + url);
        }
        try {
            Files.write(destination, Mp4Fixtures.valid());
        } catch (IOException e) {
            throw new ClipException("No se pudo escribir el clip de prueba.", e);
        }
    }
}
