package com.aishorts.engine.montage;

import com.aishorts.engine.domain.Scene;
import com.aishorts.engine.domain.Story;
import com.aishorts.engine.subtitles.BilingualCaptions;
import com.aishorts.engine.subtitles.StorySubtitleBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Monta el video final de una historia a partir de archivos que ya están en
 * disco: el clip de cada escena en {@code clipsDir/<sceneId>.mp4} y el audio
 * de narración que la escena ya tiene guardado (narrationAudioPath).
 *
 * No llama a Higgsfield ni a ElevenLabs: no genera, no sintetiza, no baja
 * nada de la red. Si falta un clip o un audio, falla con la lista completa
 * de lo que falta ANTES de armar los .srt y de correr ffmpeg.
 *
 * Deja todo en {@code outputDir/<storyId>/}: {@code <storyId>.mp4} (el video
 * final), {@code <storyId>.es.srt} (el que se quema), {@code <storyId>.en.srt}
 * (pista para subir aparte) y {@code work/} con los intermedios de ffmpeg.
 */
public final class StoryMontageService {

    private final VideoMontageBuilder montageBuilder;
    private final StorySubtitleBuilder subtitleBuilder;
    private final Path clipsDir;
    private final Path narrationAudioDir;
    private final Path outputDir;

    public StoryMontageService(
            VideoMontageBuilder montageBuilder,
            StorySubtitleBuilder subtitleBuilder,
            Path clipsDir,
            Path narrationAudioDir,
            Path outputDir
    ) {
        this.montageBuilder = montageBuilder;
        this.subtitleBuilder = subtitleBuilder;
        this.clipsDir = clipsDir;
        this.narrationAudioDir = narrationAudioDir;
        this.outputDir = outputDir;
    }

    public MontageResult montage(Story story) {
        List<MissingMontageInput> missing = findMissingInputs(story);
        if (!missing.isEmpty()) {
            throw new MissingMontageInputsException(story.id(), missing);
        }

        Path storyOutputDir = outputDir.resolve(story.id()).toAbsolutePath();
        Path srtEsPath = storyOutputDir.resolve(story.id() + ".es.srt");
        Path srtEnPath = storyOutputDir.resolve(story.id() + ".en.srt");
        Path videoPath = storyOutputDir.resolve(story.id() + ".mp4");
        Path workDir = storyOutputDir.resolve("work");

        BilingualCaptions captions = subtitleBuilder.build(story);
        try {
            Files.createDirectories(storyOutputDir);
            Files.writeString(srtEsPath, captions.srtEs(), StandardCharsets.UTF_8);
            Files.writeString(srtEnPath, captions.srtEn(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new FfmpegException("No se pudieron escribir los .srt en '" + storyOutputDir + "'.", e);
        }

        montageBuilder.buildFromLocalClips(story, clipsDir, workDir, srtEsPath, videoPath);
        return new MontageResult(videoPath, srtEsPath, srtEnPath, workDir);
    }

    /** Todos los clips y audios que faltan, en el orden de story.scenes(); vacío si está todo. */
    public List<MissingMontageInput> findMissingInputs(Story story) {
        List<MissingMontageInput> missing = new ArrayList<>();
        for (Scene scene : story.scenes()) {
            Path clip = VideoMontageBuilder.localClipPath(clipsDir, scene).toAbsolutePath();
            if (!Files.isRegularFile(clip)) {
                missing.add(new MissingMontageInput(scene.id(), "clip", clip.toString(),
                        scene.id() + ": falta el clip de video '" + clip.getFileName() + "', se espera en " + clip));
            }

            if (scene.narrationAudioPath() == null) {
                Path expected = narrationAudioDir.resolve(scene.id() + ".mp3").toAbsolutePath();
                missing.add(new MissingMontageInput(scene.id(), "audio", expected.toString(),
                        scene.id() + ": falta el audio de narración '" + expected.getFileName()
                                + "' — la escena no tiene narrationAudioPath guardado (se esperaría en "
                                + expected + ")"));
            } else {
                Path audio = Path.of(scene.narrationAudioPath()).toAbsolutePath();
                if (!Files.isRegularFile(audio)) {
                    missing.add(new MissingMontageInput(scene.id(), "audio", audio.toString(),
                            scene.id() + ": falta el audio de narración '" + audio.getFileName()
                                    + "', se espera en " + audio));
                }
            }
        }
        return missing;
    }
}
