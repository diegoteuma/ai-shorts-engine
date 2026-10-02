package com.aishorts.engine.montage;

import com.aishorts.engine.difficulty.TierRecommendation;
import com.aishorts.engine.domain.GenerationTier;
import com.aishorts.engine.domain.Scene;
import com.aishorts.engine.domain.SceneRole;
import com.aishorts.engine.domain.Story;
import com.aishorts.engine.higgsfield.EstimateResponse;
import com.aishorts.engine.subtitles.StorySubtitleBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.aishorts.engine.montage.SyntheticMedia.generateColorClip;
import static com.aishorts.engine.montage.SyntheticMedia.generateSineAudio;
import static com.aishorts.engine.montage.SyntheticMedia.probeResolution;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * StoryMontageService contra archivos locales: valida clips/audios antes de
 * tocar ffmpeg y, con todo en disco, arma el video con ffmpeg real.
 *
 * Las escenas tienen a propósito un generatedAssetUrl que no resuelve
 * (higgsfield.invalid): si el montaje intentara bajar algo de la red en vez
 * de usar clipsDir, el test fallaría.
 */
class StoryMontageServiceTest {

    private static final SceneRole[] ROLES = {
            SceneRole.GANCHO, SceneRole.EXPLICACION, SceneRole.CONTEXTO,
            SceneRole.GIRO, SceneRole.CONSECUENCIA, SceneRole.CIERRE
    };
    private static final String[] COLORS = {"red", "orange", "yellow", "green", "blue", "purple"};

    @TempDir
    Path tempDir;

    @Test
    void missingClipsAndAudios_failsListingEveryExpectedPath_beforeRunningFfmpeg() throws Exception {
        Path clipsDir = tempDir.resolve("clips");
        Path audioDir = tempDir.resolve("audio");
        Path outputDir = tempDir.resolve("output");
        Files.createDirectories(clipsDir);
        Files.createDirectories(audioDir);

        Story story = storyWithSixScenes("1", audioDir);
        // Solo la primera escena tiene su clip; a la segunda le falta además el audio.
        generateColorClip(clipsDir.resolve("tunguska-gancho.mp4"), "red");
        Files.delete(audioDir.resolve("tunguska-explicacion.mp3"));

        // Un binario de ffmpeg que no existe: si el servicio llegara a correr
        // ffmpeg fallaría con FfmpegException, no con MissingMontageInputsException.
        StoryMontageService service = serviceWith(
                new FfmpegRunner("ffmpeg-que-no-existe", 5), clipsDir, audioDir, outputDir);

        assertThatThrownBy(() -> service.montage(story))
                .isInstanceOf(MissingMontageInputsException.class)
                .satisfies(e -> {
                    List<MissingMontageInput> missing = ((MissingMontageInputsException) e).missing();
                    assertThat(missing).extracting(MissingMontageInput::sceneId, MissingMontageInput::kind)
                            .containsExactly(
                                    org.assertj.core.groups.Tuple.tuple("tunguska-explicacion", "clip"),
                                    org.assertj.core.groups.Tuple.tuple("tunguska-explicacion", "audio"),
                                    org.assertj.core.groups.Tuple.tuple("tunguska-contexto", "clip"),
                                    org.assertj.core.groups.Tuple.tuple("tunguska-giro", "clip"),
                                    org.assertj.core.groups.Tuple.tuple("tunguska-consecuencia", "clip"),
                                    org.assertj.core.groups.Tuple.tuple("tunguska-cierre", "clip"));
                    assertThat(missing.get(0).expectedPath())
                            .isEqualTo(clipsDir.resolve("tunguska-explicacion.mp4").toAbsolutePath().toString());
                    assertThat(missing.get(1).expectedPath())
                            .isEqualTo(audioDir.resolve("tunguska-explicacion.mp3").toAbsolutePath().toString());
                    assertThat(e.getMessage())
                            .contains("tunguska-explicacion: falta el clip de video 'tunguska-explicacion.mp4', se espera en "
                                    + clipsDir.resolve("tunguska-explicacion.mp4").toAbsolutePath())
                            .contains("tunguska-explicacion: falta el audio de narración 'tunguska-explicacion.mp3', se espera en "
                                    + audioDir.resolve("tunguska-explicacion.mp3").toAbsolutePath());
                });

        // Falló antes de escribir .srt o intermedios.
        assertThat(outputDir).doesNotExist();
    }

    @Test
    void sceneWithoutNarrationAudioPath_isReportedWithTheExpectedAudioLocation() throws Exception {
        Path clipsDir = tempDir.resolve("clips");
        Path audioDir = tempDir.resolve("audio");
        Files.createDirectories(clipsDir);

        Scene scene = new Scene("2-gancho", SceneRole.GANCHO, 1, "narración", "prompt", Duration.ofSeconds(1));
        scene.proposeTier(new TierRecommendation(GenerationTier.STANDARD, 20, "test"));
        scene.approvePrompt();
        generateColorClip(clipsDir.resolve("2-gancho.mp4"), "red");
        Story story = new Story("2", "topic", "título", List.of(scene));

        StoryMontageService service = serviceWith(new FfmpegRunner(), clipsDir, audioDir, tempDir.resolve("output"));

        List<MissingMontageInput> missing = service.findMissingInputs(story);
        assertThat(missing).hasSize(1);
        assertThat(missing.get(0).kind()).isEqualTo("audio");
        assertThat(missing.get(0).expectedPath()).isEqualTo(audioDir.resolve("2-gancho.mp3").toAbsolutePath().toString());
        assertThat(missing.get(0).description()).contains("no tiene narrationAudioPath guardado");
    }

    @Test
    void withAllLocalFiles_buildsVerticalMp4AndBothSrts_withoutTouchingTheNetwork() throws Exception {
        Path clipsDir = tempDir.resolve("clips");
        Path audioDir = tempDir.resolve("audio");
        Path outputDir = tempDir.resolve("output");
        Files.createDirectories(clipsDir);
        Files.createDirectories(audioDir);

        Story story = storyWithSixScenes("1", audioDir);
        for (int i = 0; i < story.scenes().size(); i++) {
            generateColorClip(clipsDir.resolve(story.scenes().get(i).id() + ".mp4"), COLORS[i]);
        }

        MontageResult result = serviceWith(new FfmpegRunner(), clipsDir, audioDir, outputDir).montage(story);

        Path storyOutputDir = outputDir.resolve("1").toAbsolutePath();
        assertThat(result.videoPath()).isEqualTo(storyOutputDir.resolve("1.mp4"));
        assertThat(result.srtEsPath()).isEqualTo(storyOutputDir.resolve("1.es.srt"));
        assertThat(result.srtEnPath()).isEqualTo(storyOutputDir.resolve("1.en.srt"));
        assertThat(result.videoPath()).isRegularFile();
        assertThat(probeResolution(result.videoPath())).containsExactly(1080, 1920);
        assertThat(Files.readString(result.srtEsPath())).contains("narración tunguska-gancho");
        assertThat(Files.readString(result.srtEnPath())).contains("EN narración tunguska-gancho");
        // El clip se copió del disco local a work/, en el orden de story.scenes().
        assertThat(result.workDir().resolve("tunguska-gancho-raw.mp4")).isRegularFile();
        assertThat(Files.readString(result.workDir().resolve("concat-list.txt")))
                .containsSubsequence("tunguska-gancho", "tunguska-explicacion", "tunguska-contexto", "tunguska-giro", "tunguska-consecuencia", "tunguska-cierre");
    }

    // --- helpers ----------------------------------------------------------------------------

    private static StoryMontageService serviceWith(FfmpegRunner ffmpegRunner, Path clipsDir, Path audioDir, Path outputDir) {
        return new StoryMontageService(
                new VideoMontageBuilder(ffmpegRunner, new AssetDownloader()),
                new StorySubtitleBuilder(texts -> texts.stream().map(t -> "EN " + t).toList()),
                clipsDir, audioDir, outputDir);
    }

    /** 6 escenas COMPLETED con audio .mp3 real en audioDir y un generatedAssetUrl que no resuelve. */
    private static Story storyWithSixScenes(String storyId, Path audioDir) throws Exception {
        List<Scene> scenes = new ArrayList<>();
        for (int i = 0; i < ROLES.length; i++) {
            String id = storyId + "-" + ROLES[i].name().toLowerCase();
            Scene scene = new Scene(id, ROLES[i], i + 1, "narración " + id, "prompt " + id, Duration.ofSeconds(1));
            scene.proposeTier(new TierRecommendation(GenerationTier.STANDARD, 20, "test"));
            scene.approvePrompt();

            Path audio = audioDir.resolve(id + ".mp3");
            generateSineAudio(audio);
            scene.attachNarrationAudio(audio.toString(), Duration.ofSeconds(1));

            scene.recordCostEstimate(new EstimateResponse(new BigDecimal("0.10"), "USD", "model", Map.of()), "model");
            scene.approveCost();
            scene.startGeneration("req-" + id, null);
            scene.completeGeneration("https://higgsfield.invalid/expired/" + id + ".mp4");
            scenes.add(scene);
        }
        return new Story(storyId, "topic", "título", scenes);
    }
}
