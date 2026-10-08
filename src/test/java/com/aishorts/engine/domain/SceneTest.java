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

    // --- revisión de un prompt ya aprobado ------------------------------------------------

    @Test
    void approvePrompt_onAlreadyApprovedScene_appliesTheRevisedPrompt_andDiscardsTheStaleCost() {
        Scene scene = newScene();
        scene.proposeTier(new TierRecommendation(GenerationTier.STANDARD, 40, "test"));
        scene.approvePrompt();
        scene.recordCostEstimate(estimate("0.35"), "higgsfield-ai/soul/standard");
        scene.approveCost();

        scene.approvePrompt("prompt visual revisado", null);

        assertThat(scene.visualPrompt()).isEqualTo("prompt visual revisado");
        assertThat(scene.isPromptApproved()).isTrue();
        assertThat(scene.costStatus()).isEqualTo(SceneCostStatus.NOT_ESTIMATED);
        assertThat(scene.costEstimate()).isNull();
        assertThat(scene.chosenModelId()).isNull();
        assertThat(scene.needsCostEstimate()).isTrue();
    }

    @Test
    void approvePrompt_onAlreadyApprovedScene_withTierOverride_changesTheTier() {
        Scene scene = newScene();
        scene.proposeTier(new TierRecommendation(GenerationTier.STANDARD, 40, "test"));
        scene.approvePrompt();

        scene.approvePrompt(null, GenerationTier.PREMIUM);

        assertThat(scene.chosenTier()).isEqualTo(GenerationTier.PREMIUM);
    }

    @Test
    void approvePrompt_onAlreadyApprovedScene_withoutChanges_keepsTheCost() {
        Scene scene = newScene();
        scene.proposeTier(new TierRecommendation(GenerationTier.STANDARD, 40, "test"));
        scene.approvePrompt();
        scene.recordCostEstimate(estimate("0.35"), "higgsfield-ai/soul/standard");
        scene.approveCost();

        scene.approvePrompt();
        scene.approvePrompt("prompt visual", GenerationTier.STANDARD);

        assertThat(scene.isCostApproved()).isTrue();
        assertThat(scene.costEstimate()).isNotNull();
    }

    @Test
    void approvePrompt_revisionAfterGenerationStarted_isRejected_untilResetGeneration() {
        Scene scene = completedScene();

        assertThatIllegalStateException().isThrownBy(() -> scene.approvePrompt("otro prompt", null));
        assertThat(scene.visualPrompt()).isEqualTo("prompt visual");
        assertThat(scene.generationStatus()).isEqualTo(GenerationStatus.COMPLETED);

        scene.resetForRegeneration();
        scene.approvePrompt("otro prompt", null);
        assertThat(scene.visualPrompt()).isEqualTo("otro prompt");
    }

    @Test
    void updateNarration_withNewText_discardsTheStaleAudio() {
        Scene scene = newScene();
        scene.proposeTier(new TierRecommendation(GenerationTier.STANDARD, 40, "test"));
        scene.approvePrompt();
        scene.attachNarrationAudio("audio/scene-1.mp3", Duration.ofSeconds(7));

        scene.updateNarration("narración de prueba", Duration.ofSeconds(5));
        assertThat(scene.hasNarrationAudio()).isTrue();

        scene.updateNarration("otra narración", Duration.ofSeconds(4));
        assertThat(scene.hasNarrationAudio()).isFalse();
        assertThat(scene.narrationText()).isEqualTo("otra narración");
    }

    // --- resetForRegeneration ------------------------------------------------------------

    private static Scene completedScene() {
        Scene scene = newScene();
        scene.proposeTier(new TierRecommendation(GenerationTier.PREMIUM, 70, "alto impacto visual"));
        scene.approvePrompt();
        scene.attachNarrationAudio("audio/scene-1.mp3", Duration.ofSeconds(7));
        scene.recordCostEstimate(estimate("1.20"), "higgsfield-ai/soul-premium/cinema");
        scene.approveCost();
        scene.startGeneration("req-1", "https://status/req-1");
        scene.completeGeneration("https://cdn.example/req-1.mp4");
        return scene;
    }

    @Test
    void resetForRegeneration_onCompletedScene_clearsCostAndGenerationButKeepsNarrationPromptAndTier() {
        Scene scene = completedScene();

        scene.resetForRegeneration();

        // La narración real y las decisiones de la puerta 1 no se tocan.
        assertThat(scene.narrationAudioPath()).isEqualTo("audio/scene-1.mp3");
        assertThat(scene.targetDuration()).isEqualTo(Duration.ofSeconds(7));
        assertThat(scene.promptStatus()).isEqualTo(SceneApprovalStatus.APPROVED);
        assertThat(scene.visualPrompt()).isEqualTo("prompt visual");
        assertThat(scene.chosenTier()).isEqualTo(GenerationTier.PREMIUM);

        // Lo técnico de costo/generación se descarta por completo.
        assertThat(scene.costStatus()).isEqualTo(SceneCostStatus.NOT_ESTIMATED);
        assertThat(scene.costEstimate()).isNull();
        assertThat(scene.costRejectionNote()).isNull();
        assertThat(scene.chosenModelId()).isNull();
        assertThat(scene.generationStatus()).isEqualTo(GenerationStatus.NOT_STARTED);
        assertThat(scene.higgsfieldRequestId()).isNull();
        assertThat(scene.higgsfieldStatusUrl()).isNull();
        assertThat(scene.generatedAssetUrl()).isNull();
        assertThat(scene.generationFailureReason()).isNull();
    }

    @Test
    void resetForRegeneration_onFailedScene_clearsFailureReason() {
        Scene scene = completedScene();
        scene.failGeneration("Higgsfield marcó el contenido como NSFW.");
        assertThat(scene.generationStatus()).isEqualTo(GenerationStatus.FAILED);

        scene.resetForRegeneration();

        assertThat(scene.generationStatus()).isEqualTo(GenerationStatus.NOT_STARTED);
        assertThat(scene.generationFailureReason()).isNull();
    }

    @Test
    void resetForRegeneration_requiresApprovedPrompt() {
        Scene scene = newScene();

        assertThatIllegalStateException().isThrownBy(scene::resetForRegeneration);
    }

    @Test
    void resetForRegeneration_rejectsSceneWithGenerationQueued() {
        Scene scene = completedScene();
        // Vuelve a encolar sobre el request anterior, simulando una nueva
        // generación que todavía no terminó.
        scene.failGeneration("reset de prueba");
        scene.resetForRegeneration();
        scene.recordCostEstimate(estimate("1.20"), "higgsfield-ai/soul-premium/cinema");
        scene.approveCost();
        scene.startGeneration("req-2", "https://status/req-2");
        assertThat(scene.generationStatus()).isEqualTo(GenerationStatus.QUEUED);

        assertThatIllegalStateException().isThrownBy(scene::resetForRegeneration);

        // El estado no cambió: sigue QUEUED con el mismo request.
        assertThat(scene.generationStatus()).isEqualTo(GenerationStatus.QUEUED);
        assertThat(scene.higgsfieldRequestId()).isEqualTo("req-2");
    }

    @Test
    void resetForRegeneration_rejectsSceneWithGenerationInProgress() {
        Scene scene = completedScene();
        scene.failGeneration("reset de prueba");
        scene.resetForRegeneration();
        scene.recordCostEstimate(estimate("1.20"), "higgsfield-ai/soul-premium/cinema");
        scene.approveCost();
        scene.startGeneration("req-3", "https://status/req-3");
        scene.markInProgress();
        assertThat(scene.generationStatus()).isEqualTo(GenerationStatus.IN_PROGRESS);

        assertThatIllegalStateException().isThrownBy(scene::resetForRegeneration);

        // El estado no cambió: sigue IN_PROGRESS con el mismo request.
        assertThat(scene.generationStatus()).isEqualTo(GenerationStatus.IN_PROGRESS);
        assertThat(scene.higgsfieldRequestId()).isEqualTo("req-3");
    }

    @Test
    void resetForRegeneration_allowsTheFullPipelineToRunAgainAfterward() {
        Scene scene = completedScene();

        scene.resetForRegeneration();

        // Los guards existentes dejan avanzar de nuevo el camino completo.
        scene.recordCostEstimate(estimate("0.99"), "bytedance/seedance-2.0/text-to-video");
        assertThat(scene.costStatus()).isEqualTo(SceneCostStatus.ESTIMATED);
        scene.approveCost();
        assertThat(scene.isCostApproved()).isTrue();
        scene.startGeneration("req-new", "https://status/req-new");
        assertThat(scene.generationStatus()).isEqualTo(GenerationStatus.QUEUED);
        scene.completeGeneration("https://cdn.example/req-new.mp4");
        assertThat(scene.generationStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(scene.generatedAssetUrl()).isEqualTo("https://cdn.example/req-new.mp4");
    }
}
