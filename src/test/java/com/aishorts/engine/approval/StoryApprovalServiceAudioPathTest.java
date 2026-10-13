package com.aishorts.engine.approval;

import com.aishorts.engine.difficulty.DefaultSceneDifficultyScorer;
import com.aishorts.engine.difficulty.TierRecommendation;
import com.aishorts.engine.domain.GenerationTier;
import com.aishorts.engine.domain.Scene;
import com.aishorts.engine.domain.SceneRole;
import com.aishorts.engine.domain.Story;
import com.aishorts.engine.duration.WordsPerSecondDurationEstimator;
import com.aishorts.engine.higgsfield.HiggsfieldConfig;
import com.aishorts.engine.higgsfield.KnownHiggsfieldPricing;
import com.aishorts.engine.tts.TtsResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Defensa en profundidad de la síntesis de audio: aunque una escena llegue con
 * un id que escapa de la carpeta (no debería: el id de historia ya se valida
 * en la entrada y en el repositorio), el mp3 nunca se escribe fuera de audioDir.
 */
class StoryApprovalServiceAudioPathTest {

    @TempDir
    Path tmp;

    @Test
    void aSceneIdThatWouldEscapeAudioDirFailsAndWritesNothingOutside() throws IOException {
        Path audioDir = tmp.resolve("audio");
        StoryApprovalService service = new StoryApprovalService(
                null, config(), new DefaultSceneDifficultyScorer(), WordsPerSecondDurationEstimator.neutralSpanish(),
                text -> new TtsResult(new byte[]{1, 2, 3}, Duration.ofSeconds(3)));
        Scene evil = approvedScene("../fuera");
        Scene ok = approvedScene("ok-gancho");
        Story story = new Story("ok", "tema", "título", List.of(evil, ok));

        BatchResult result = service.synthesizeNarrationForApprovedScenes(story, audioDir);

        assertThat(result.succeededSceneIds()).containsExactly("ok-gancho");
        assertThat(result.failedSceneIds()).containsOnlyKeys("../fuera");
        assertThat(result.failedSceneIds().get("../fuera")).contains("fuera de");
        assertThat(evil.hasNarrationAudio()).isFalse();
        assertThat(tmp.resolve("fuera.mp3")).doesNotExist();
        assertThat(audioDir.resolve("ok-gancho.mp3")).exists();
        try (var files = Files.walk(tmp)) {
            assertThat(files.filter(Files::isRegularFile).toList()).containsExactly(audioDir.resolve("ok-gancho.mp3"));
        }
    }

    private static Scene approvedScene(String id) {
        Scene scene = new Scene(id, SceneRole.GANCHO, 1, "narración", "prompt", Duration.ofSeconds(4));
        scene.proposeTier(new TierRecommendation(GenerationTier.STANDARD, 20, "test"));
        scene.approvePrompt();
        return scene;
    }

    private static HiggsfieldConfig config() {
        return new HiggsfieldConfig("http://higgsfield.invalid", "key-id", "key-secret",
                Map.of(GenerationTier.STANDARD, KnownHiggsfieldPricing.STANDARD_MODEL_ID,
                        GenerationTier.PREMIUM, KnownHiggsfieldPricing.PREMIUM_MODEL_ID),
                KnownHiggsfieldPricing.defaults());
    }
}
