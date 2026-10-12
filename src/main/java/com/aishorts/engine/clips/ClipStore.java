package com.aishorts.engine.clips;

import com.aishorts.engine.domain.Scene;
import com.aishorts.engine.domain.Story;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Guarda localmente (clipsDir/{idEscena}.mp4) los clips que Higgsfield ya
 * generó, para no depender de que sus URLs sigan vivas (no se sabe cuánto
 * duran: no está documentado en el proyecto).
 *
 * Reglas:
 * <ul>
 *   <li>Solo actúa cuando TODAS las escenas de la historia están COMPLETED.</li>
 *   <li>Idempotente: una escena con copia registrada cuyo archivo sigue siendo
 *       un MP4 válido del tamaño esperado no se vuelve a descargar. Un archivo
 *       en disco que la escena NO tiene registrado se ignora (podría ser de
 *       una generación anterior) y se pisa.</li>
 *   <li>Un fallo no aborta: la escena queda con su URL, sin copia local y con
 *       el motivo; reintentar es volver a llamar (poll-generation).</li>
 *   <li>Se descarga a un temporal en el mismo directorio, se valida y se
 *       publica con un move atómico: nunca queda un .mp4 a medias.</li>
 *   <li>Antes de descargar exige un mínimo de disco libre.</li>
 * </ul>
 * Los ids de escena se restringen a [A-Za-z0-9_-] porque forman el nombre de archivo.
 */
public final class ClipStore {

    private static final Logger log = LoggerFactory.getLogger(ClipStore.class);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]+");

    private final Path clipsDir;
    private final ClipDownloader downloader;
    private final int expectedWidth;
    private final int expectedHeight;
    private final long minFreeBytes;

    public ClipStore(Path clipsDir, ClipDownloader downloader, int expectedWidth, int expectedHeight, long minFreeBytes) {
        this.clipsDir = clipsDir;
        this.downloader = downloader;
        this.expectedWidth = expectedWidth;
        this.expectedHeight = expectedHeight;
        this.minFreeBytes = minFreeBytes;
    }

    /** El directorio se crea recién al descargar el primer clip (no al arrancar la app). */
    public Path clipsDir() {
        return clipsDir;
    }

    public Path resolve(String relativePath) {
        Path resolved = clipsDir.resolve(relativePath).normalize();
        if (!resolved.startsWith(clipsDir.normalize())) {
            throw new ClipException("Ruta de clip fuera del directorio de clips: " + relativePath);
        }
        return resolved;
    }

    public ClipSyncResult syncIfAllCompleted(Story story) {
        if (!story.allScenesCompleted()) {
            return ClipSyncResult.notAttempted();
        }
        List<String> stored = new ArrayList<>();
        List<String> already = new ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();

        for (Scene scene : story.scenes()) {
            try {
                if (hasValidLocalClip(scene)) {
                    already.add(scene.id());
                    continue;
                }
                download(scene);
                stored.add(scene.id());
            } catch (RuntimeException e) {
                String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log.warn("No se pudo guardar el clip de la escena '{}': {}", scene.id(), reason);
                scene.recordClipDownloadFailure(reason);
                failed.put(scene.id(), reason);
            }
        }
        return new ClipSyncResult(true, stored, already, failed);
    }

    /** Borra la copia local de las escenas indicadas (tras un reset-generation). Un fallo de borrado solo se registra. */
    public void deleteClips(Collection<String> sceneIds) {
        for (String sceneId : sceneIds) {
            if (!SAFE_ID.matcher(sceneId).matches()) {
                continue;
            }
            Path file = clipsDir.resolve(sceneId + ".mp4");
            try {
                if (Files.deleteIfExists(file)) {
                    log.info("Clip local borrado: {}", file);
                }
            } catch (IOException e) {
                log.warn("No se pudo borrar el clip local '{}': {}", file, e.getMessage());
            }
        }
    }

    private boolean hasValidLocalClip(Scene scene) {
        if (!scene.hasLocalClip()) {
            return false;
        }
        try {
            Path file = resolve(scene.localClipPath());
            if (!Files.isRegularFile(file)) {
                return false;
            }
            Mp4Probe.Info info = Mp4Probe.probe(file);
            return info.width() == expectedWidth && info.height() == expectedHeight;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void download(Scene scene) {
        String id = scene.id();
        if (!SAFE_ID.matcher(id).matches()) {
            throw new ClipException("El id de escena '" + id + "' tiene caracteres no permitidos para un nombre de archivo.");
        }
        Path tmp = null;
        try {
            Files.createDirectories(clipsDir);
            long usable = Files.getFileStore(clipsDir).getUsableSpace();
            if (usable < minFreeBytes) {
                throw new ClipException("Poco espacio en disco: " + usable + " bytes libres en '" + clipsDir
                        + "', se exige un mínimo de " + minFreeBytes + ".");
            }
            tmp = Files.createTempFile(clipsDir, "clip-" + id + "-", ".part");
            downloader.download(scene.generatedAssetUrl(), tmp);

            Mp4Probe.Info info = Mp4Probe.probe(tmp);
            if (info.width() != expectedWidth || info.height() != expectedHeight) {
                throw new ClipException("El clip es de " + info.width() + "x" + info.height()
                        + " y se esperaba " + expectedWidth + "x" + expectedHeight
                        + " (revisar aspect_ratio/resolution del modelo).");
            }
            String fileName = id + ".mp4";
            Files.move(tmp, clipsDir.resolve(fileName),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            tmp = null;
            scene.attachLocalClip(fileName);
        } catch (IOException e) {
            throw new ClipException("No se pudo guardar el clip en disco: " + e.getMessage(), e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // queda un .part suelto; no enmascarar el error original.
                }
            }
        }
    }
}
