package com.aishorts.engine.clips;

import com.aishorts.engine.difficulty.TierRecommendation;
import com.aishorts.engine.domain.GenerationTier;
import com.aishorts.engine.domain.Scene;
import com.aishorts.engine.domain.SceneRole;
import com.aishorts.engine.domain.SceneSnapshot;
import com.aishorts.engine.domain.StorySnapshot;
import com.aishorts.engine.higgsfield.EstimateResponse;
import com.aishorts.engine.persistence.SnapshotMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/** Campos de copia local del clip en Scene y su (de)serialización. */
class SceneLocalClipTest {

    private static Scene queuedScene() {
        Scene scene = new Scene("s1-gancho", SceneRole.GANCHO, 1, "narración", "prompt", Duration.ofSeconds(5));
        scene.proposeTier(new TierRecommendation(GenerationTier.STANDARD, 40, "test"));
        scene.approvePrompt();
        scene.recordCostEstimate(new EstimateResponse(new BigDecimal("0.35"), "USD", "m", Map.of()), "m");
        scene.approveCost();
        scene.startGeneration("req-1", "https://status/req-1");
        return scene;
    }

    private static Scene completedScene() {
        Scene scene = queuedScene();
        scene.completeGeneration("https://cdn/1.mp4");
        return scene;
    }

    @Test
    void attachingAClipRequiresACompletedGeneration() {
        Scene scene = queuedScene();

        assertThatIllegalStateException().isThrownBy(() -> scene.attachLocalClip("s1-gancho.mp4"));
        assertThatIllegalStateException().isThrownBy(() -> scene.recordClipDownloadFailure("x"));
        assertThat(scene.hasLocalClip()).isFalse();
    }

    @Test
    void attachingAClipClearsThePreviousFailureReason() {
        Scene scene = completedScene();
        scene.recordClipDownloadFailure("HTTP 403");

        scene.attachLocalClip("s1-gancho.mp4");

        assertThat(scene.localClipPath()).isEqualTo("s1-gancho.mp4");
        assertThat(scene.localClipFailureReason()).isNull();
    }

    @Test
    void resetForRegenerationForgetsTheLocalClipAndTheFailure() {
        Scene withClip = completedScene();
        withClip.attachLocalClip("s1-gancho.mp4");
        Scene withFailure = completedScene();
        withFailure.recordClipDownloadFailure("HTTP 403");

        withClip.resetForRegeneration();
        withFailure.resetForRegeneration();

        assertThat(withClip.localClipPath()).isNull();
        assertThat(withFailure.localClipFailureReason()).isNull();
    }

    @Test
    void theLocalClipSurvivesASnapshotRoundTripThroughTheJsonMapper() {
        Scene scene = completedScene();
        scene.attachLocalClip("s1-gancho.mp4");
        StorySnapshot story = new StorySnapshot("s1", "tema", "título", List.of(scene.toSnapshot()));

        Map<String, Object> asMap = SnapshotMapper.toMap(story);
        Scene restored = Scene.fromSnapshot(SnapshotMapper.toStorySnapshot(asMap).scenes().get(0));

        assertThat(restored.localClipPath()).isEqualTo("s1-gancho.mp4");
        assertThat(restored.localClipFailureReason()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void jsonSavedBeforeTheFieldsExistedStillLoads() {
        // Los .json guardados antes de esta funcionalidad no traen las claves nuevas.
        Scene scene = completedScene();
        StorySnapshot story = new StorySnapshot("s1", "tema", "título", List.of(scene.toSnapshot()));
        Map<String, Object> asMap = SnapshotMapper.toMap(story);
        List<Object> scenes = new ArrayList<>((List<Object>) asMap.get("scenes"));
        Map<String, Object> legacyScene = new java.util.LinkedHashMap<>((Map<String, Object>) scenes.get(0));
        legacyScene.remove("localClipPath");
        legacyScene.remove("localClipFailureReason");
        scenes.set(0, legacyScene);
        Map<String, Object> legacy = new java.util.LinkedHashMap<>(asMap);
        legacy.put("scenes", scenes);

        SceneSnapshot loaded = SnapshotMapper.toStorySnapshot(legacy).scenes().get(0);

        assertThat(loaded.localClipPath()).isNull();
        assertThat(loaded.localClipFailureReason()).isNull();
    }
}
