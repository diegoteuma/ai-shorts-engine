package com.aishorts.engine.domain;

import com.aishorts.engine.difficulty.TierRecommendation;
import com.aishorts.engine.higgsfield.EstimateResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Scene es la guardiana de su propio ciclo de vida: nunca se debe poder
 * estimar costo sin prompt aprobado, ni generar sin costo aprobado. Estos
 * tests verifican que esos guards (IllegalStateException) realmente
 * bloquean el orden incorrecto, y que el camino feliz completo — incluido
 * el snapshot/restore usado por persistencia — no lo hace.
 */
class SceneTest {

    private static Scene newScene() {
        return new Scene("scene-1", SceneRole.GANCHO, 1, "narración de prueba", "prompt visual", Duration.ofSeconds(5));
    }

    private static EstimateResponse estimate(String cost) {
        return new EstimateResponse(new BigDecimal(cost), "USD", "higgsfield-ai/soul/standard", Map.of());
    }

    @Test
    void recordCostEstimateRequiresApprovedPrompt() {
        Scene scene = newScene();

        assertThatIllegalStateException()
                .isThrownBy(() -> scene.recordCostEstimate(estimate("0.35"), "higgsfield-ai/soul/standard"));
        assertThat(scene.costStatus()).isEqualTo(SceneCostStatus.NOT_ESTIMATED);
    }

    @Test
    void startGenerationRequiresApprovedCost() {
        Scene scene = newScene();
        scene.proposeTier(new TierRecommendation(GenerationTier.STANDARD, 40, "test"));
        scene.approvePrompt();
        scene.recordCostEstimate(estimate("0.35"), "higgsfield-ai/soul/standard");

        // El costo quedó ESTIMATED, todavía no APPROVED.
        assertThatIllegalStateException()
                .isThrownBy(() -> scene.startGeneration("req-1", "https://status/req-1"));
        assertThat(scene.generationStatus()).isEqualTo(GenerationStatus.NOT_STARTED);
    }

    @Test
    void approveCostAndRejectCostRequireEstimatedCostFirst() {
        Scene scene = newScene();

        // Sin prompt aprobado ni costo estimado.
        assertThatIllegalStateException().isThrownBy(scene::approveCost);
        assertThatIllegalStateException().isThrownBy(() -> scene.rejectCost("nota"));

        // Con prompt aprobado pero costo todavía NOT_ESTIMATED.
        scene.proposeTier(new TierRecommendation(GenerationTier.STANDARD, 40, "test"));
        scene.approvePrompt();
        assertThatIllegalStateException().isThrownBy(scene::approveCost);
        assertThatIllegalStateException().isThrownBy(() -> scene.rejectCost("nota"));
        assertThat(scene.costStatus()).isEqualTo(SceneCostStatus.NOT_ESTIMATED);
    }

    @Test
    void happyPathFullLifecycleAndSnapshotRoundTrip() {
        Scene scene = newScene();

        scene.proposeTier(new TierRecommendation(GenerationTier.PREMIUM, 70, "alto impacto visual"));
        scene.approvePrompt();
        assertThat(scene.isPromptApproved()).isTrue();

        scene.attachNarrationAudio("audio/scene-1.mp3", Duration.ofSeconds(7));
        assertThat(scene.hasNarrationAudio()).isTrue();
        assertThat(scene.targetDuration()).isEqualTo(Duration.ofSeconds(7));

        scene.recordCostEstimate(estimate("1.20"), "higgsfield-ai/soul-premium/cinema");
        assertThat(scene.costStatus()).isEqualTo(SceneCostStatus.ESTIMATED);

        scene.approveCost();
        assertThat(scene.isCostApproved()).isTrue();

        scene.startGeneration("req-1", "https://status/req-1");
        assertThat(scene.generationStatus()).isEqualTo(GenerationStatus.QUEUED);

        scene.completeGeneration("https://cdn.example/req-1.mp4");
        assertThat(scene.generationStatus()).isEqualTo(GenerationStatus.COMPLETED);

        SceneSnapshot snapshot = scene.toSnapshot();
        Scene restored = Scene.fromSnapshot(snapshot);

        // fromSnapshot no pasa por los guards de vuelta: el estado debe quedar
        // idéntico, no requiere rehacer las transiciones.
        assertThat(restored.toSnapshot()).isEqualTo(snapshot);
        assertThat(restored.promptStatus()).isEqualTo(SceneApprovalStatus.APPROVED);
        assertThat(restored.costStatus()).isEqualTo(SceneCostStatus.APPROVED);
        assertThat(restored.generationStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(restored.generatedAssetUrl()).isEqualTo("https://cdn.example/req-1.mp4");
        assertThat(restored.targetDuration()).isEqualTo(Duration.ofSeconds(7));
        assertThat(restored.isCostApproved()).isTrue();
    }

    @Test
    void promptRejectionLeavesSceneConsistentAndRetryable() {
        Scene scene = newScene();
        scene.proposeTier(new TierRecommendation(GenerationTier.STANDARD, 40, "test"));

        scene.rejectPrompt("el fundido a negro se ve muy abrupto");

        assertThat(scene.promptStatus()).isEqualTo(SceneApprovalStatus.REJECTED);
        assertThat(scene.promptRejectionNote()).isEqualTo("el fundido a negro se ve muy abrupto");
        assertThat(scene.isPromptApproved()).isFalse();
        assertThat(scene.costStatus()).isEqualTo(SceneCostStatus.NOT_ESTIMATED);

        // Un prompt rechazado sigue bloqueando el costo, como cualquier prompt no aprobado.
        assertThatIllegalStateException()
                .isThrownBy(() -> scene.recordCostEstimate(estimate("0.35"), "model"));

        // Un rechazo no es terminal: reintentar y aprobar debe funcionar, y
        // limpiar la nota de rechazo.
        scene.approvePrompt();
        assertThat(scene.isPromptApproved()).isTrue();
        assertThat(scene.promptRejectionNote()).isNull();
    }

    @Test
    void costRejectionLeavesSceneConsistent() {
        Scene scene = newScene();
        scene.proposeTier(new TierRecommendation(GenerationTier.STANDARD, 40, "test"));
        scene.approvePrompt();
        scene.recordCostEstimate(estimate("0.35"), "model");

        scene.rejectCost("muy caro para esta escena");

        assertThat(scene.costStatus()).isEqualTo(SceneCostStatus.REJECTED);
        assertThat(scene.costRejectionNote()).isEqualTo("muy caro para esta escena");
        assertThat(scene.isCostApproved()).isFalse();

        // Un costo rechazado no puede generar...
        assertThatIllegalStateException()
                .isThrownBy(() -> scene.startGeneration("req-1", "https://status/req-1"));

        // ...ni aprobarse directamente: solo se puede aprobar/rechazar un costo
        // ESTIMATED, no uno ya REJECTED (haría falta un nuevo recordCostEstimate).
        assertThatIllegalStateException().isThrownBy(scene::approveCost);
    }
}
