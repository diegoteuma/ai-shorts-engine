package com.aishorts.engine.montage;

import com.aishorts.engine.domain.GenerationStatus;
import com.aishorts.engine.domain.Scene;
import com.aishorts.engine.domain.Story;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Arma el video final de un Short a partir de las escenas ya generadas:
 * baja los clips de Higgsfield, les pone el audio de narración de cada
 * escena, las concatena en orden y quema el .srt en español encima.
 *
 * Deliberadamente en tres pasos separados (mux por escena -> concat ->
 * quemado) en vez de un único comando ffmpeg gigante: cada paso produce
 * un archivo intermedio inspeccionable, lo que hace mucho más fácil
 * diagnosticar cuál de las tres cosas falló si algo sale mal.
 *
 * Lo que esta primera versión NO hace, a propósito, no por olvido:
 * transiciones tipo crossfade entre escenas (filtro xfade) y overlays de
 * texto puntuales (ej. la etiqueta "HIPÓTESIS" de la escena de GIRO). Se
 * dejan para una segunda vuelta porque agregan filtergraphs bastante más
 * complejos de verificar bien en un primer paso.
 */
public final class VideoMontageBuilder {

    private static final int OUTPUT_WIDTH = 1080;
    private static final int OUTPUT_HEIGHT = 1920;

    private final FfmpegRunner ffmpegRunner;
    private final AssetDownloader assetDownloader;

    public VideoMontageBuilder(FfmpegRunner ffmpegRunner, AssetDownloader assetDownloader) {
        this.ffmpegRunner = ffmpegRunner;
        this.assetDownloader = assetDownloader;
    }

    /**
     * @param story      la historia, con todas sus escenas ya en
     *                   GenerationStatus.COMPLETED y con audio de narración
     *                   adjunto (ver {@link #requireReadyForMontage(Story)}).
     * @param workDir    carpeta para los archivos intermedios (clips bajados,
     *                   clips con audio, la lista de concat, el video
     *                   concatenado sin subtítulos). No se borra al final a
     *                   propósito, para poder inspeccionar cada paso si algo
     *                   salió mal.
     * @param srtEsPath  el .srt en español a quemar (debe ser el mismo que se
     *                   sube como pista, para que coincida con el audio).
     * @param outputPath dónde escribir el video final (mp4, 1080x1920).
     * @return outputPath, para poder encadenar llamadas.
     */
    public Path build(Story story, Path workDir, Path srtEsPath, Path outputPath) {
        requireReadyForMontage(story);
        try {
            Files.createDirectories(workDir);
        } catch (IOException e) {
            throw new FfmpegException("No se pudo crear el directorio de trabajo '" + workDir + "'.", e);
        }
        if (!Files.isRegularFile(srtEsPath)) {
            throw new FfmpegException("No existe el archivo de subtítulos '" + srtEsPath + "'.");
        }

        List<Path> perSceneClips = new ArrayList<>();
        for (Scene scene : story.scenes()) {
            perSceneClips.add(muxSceneWithAudio(scene, workDir));
        }

        Path concatenated = workDir.resolve("concatenated.mp4");
        concatenate(perSceneClips, workDir, concatenated);

        Path safeSrt = stageSubtitlesSafely(srtEsPath, workDir);
        burnSubtitles(concatenated, safeSrt, outputPath);
        return outputPath;
    }

    // --- paso 1: por escena, bajar el clip y ponerle el audio de narración -----------------

    private Path muxSceneWithAudio(Scene scene, Path workDir) {
        Path rawClip = workDir.resolve(scene.id() + "-raw.mp4");
        assetDownloader.download(scene.generatedAssetUrl(), rawClip);

        Path withAudio = workDir.resolve(scene.id() + "-with-audio.mp4");
        ffmpegRunner.run(List.of(
                "-i", rawClip.toString(),
                "-i", scene.narrationAudioPath(),
                "-map", "0:v:0",
                "-map", "1:a:0",
                "-c:v", "copy",
                "-c:a", "aac",
                "-shortest",
                withAudio.toString()
        ));
        return withAudio;
    }

    // --- paso 2: concatenar las escenas en orden --------------------------------------------

    private void concatenate(List<Path> perSceneClips, Path workDir, Path concatenated) {
        Path listFile = workDir.resolve("concat-list.txt");
        StringBuilder list = new StringBuilder();
        for (Path clip : perSceneClips) {
            list.append("file '").append(escapeForConcatList(clip.toAbsolutePath().toString())).append("'\n");
        }
        try {
            Files.writeString(listFile, list.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new FfmpegException("No se pudo escribir la lista de concat '" + listFile + "'.", e);
        }

        ffmpegRunner.run(List.of(
                "-f", "concat",
                "-safe", "0",
                "-i", listFile.toString(),
                "-c", "copy",
                concatenated.toString()
        ));
    }

    /**
     * Copia el .srt a un nombre de archivo fijo y "seguro" dentro de workDir
     * antes de usarlo en el filtro subtitles=. Verificado a mano contra
     * ffmpeg real: el filtro subtitles= re-parsea su propio argumento
     * filename con una segunda pasada de escapeo (además de la del
     * filtergraph), y un apóstrofo literal en la ruta NO sobrevive ninguna
     * combinación de backslash-escaping que se pruebe — el caracter
     * simplemente desaparece y ffmpeg termina buscando un archivo que no
     * existe. Los dos puntos y los espacios sí sobreviven bien con el
     * escapeo de {@link #escapeForFilter}. En vez de perseguir un escapeo
     * perfecto para una ruta arbitraria que puede venir de cualquier lado,
     * se elimina el problema de raíz: siempre se quema desde una copia con
     * un nombre que nosotros elegimos y que nunca tiene comillas.
     */
    private Path stageSubtitlesSafely(Path srtEsPath, Path workDir) {
        Path staged = workDir.resolve("captions.srt");
        try {
            Files.copy(srtEsPath, staged, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new FfmpegException("No se pudo copiar '" + srtEsPath + "' a '" + staged + "'.", e);
        }
        return staged;
    }

    // --- paso 3: escalar/paddear a 9:16 y quemar el .srt en español ------------------------

    private void burnSubtitles(Path concatenated, Path srtEsPath, Path outputPath) {
        String filter = "scale=" + OUTPUT_WIDTH + ":" + OUTPUT_HEIGHT + ":force_original_aspect_ratio=decrease,"
                + "pad=" + OUTPUT_WIDTH + ":" + OUTPUT_HEIGHT + ":(ow-iw)/2:(oh-ih)/2,"
                + "subtitles='" + escapeForFilter(srtEsPath.toAbsolutePath().toString()) + "'";

        ffmpegRunner.run(List.of(
                "-i", concatenated.toString(),
                "-vf", filter,
                "-c:a", "copy",
                outputPath.toString()
        ));
    }

    // --- escaping ----------------------------------------------------------------------------

    /**
     * El filtro subtitles= usa ':' como separador de sus propias opciones,
     * así que hay que escapar barras y dos puntos dentro de la ruta misma
     * (probado contra ffmpeg real: esto sí funciona bien, incluso con
     * espacios). El caso de una comilla simple ya no llega hasta acá — ver
     * {@link #stageSubtitlesSafely}.
     */
    static String escapeForFilter(String path) {
        return path
                .replace("\\", "\\\\")
                .replace(":", "\\:");
    }

    /** El demuxer concat también usa comillas simples como delimitador de cada línea. */
    static String escapeForConcatList(String path) {
        return path.replace("'", "'\\''");
    }

    // --- guard ------------------------------------------------------------------------------

    private void requireReadyForMontage(Story story) {
        List<String> problems = new ArrayList<>();
        for (Scene scene : story.scenes()) {
            if (scene.generationStatus() != GenerationStatus.COMPLETED) {
                problems.add(scene.id() + ": generación en estado " + scene.generationStatus() + ", no COMPLETED");
            } else if (scene.generatedAssetUrl() == null) {
                problems.add(scene.id() + ": no tiene generatedAssetUrl");
            }
            if (!scene.hasNarrationAudio()) {
                problems.add(scene.id() + ": no tiene audio de narración adjunto");
            }
        }
        if (!problems.isEmpty()) {
            throw new FfmpegException(
                    "No se puede armar el montaje de '" + story.id() + "' todavía:\n- "
                            + String.join("\n- ", problems));
        }
    }
}
