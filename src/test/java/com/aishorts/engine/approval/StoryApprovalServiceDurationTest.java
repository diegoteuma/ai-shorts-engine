package com.aishorts.engine.approval;

import com.aishorts.engine.difficulty.DefaultSceneDifficultyScorer;
import com.aishorts.engine.difficulty.TierRecommendation;
import com.aishorts.engine.domain.GenerationTier;
import com.aishorts.engine.domain.Scene;
import com.aishorts.engine.domain.SceneRole;
import com.aishorts.engine.domain.Story;
import com.aishorts.engine.duration.WordsPerSecondDurationEstimator;
import com.aishorts.engine.higgsfield.EstimateRequest;
import com.aishorts.engine.higgsfield.EstimateResponse;
import com.aishorts.engine.higgsfield.GenerationRequest;
import com.aishorts.engine.higgsfield.GenerationResponse;
import com.aishorts.engine.higgsfield.GenerationStatusResponse;
import com.aishorts.engine.higgsfield.HiggsfieldClient;
import com.aishorts.engine.higgsfield.HiggsfieldConfig;
import com.aishorts.engine.higgsfield.HiggsfieldException;
import com.aishorts.engine.higgsfield.KnownHiggsfieldPricing;
import com.aishorts.engine.tts.TtsService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cubre el caso real que rechazaba Higgsfield con 400 ("duration: 7 is not
 * one of [5, 10]"): buildGenerationParameters redondeaba targetDuration solo
 * al segundo entero (secondsRoundedUp), sin pasarlo por
 * HiggsfieldConfig.pricingFor(modelId).roundUpToAllowedDuration(...) — el
 * mismo método que HiggsfieldRestClient.estimateCost() ya usa correctamente
 * para calcular billedDurationSeconds, pero que nunca llegaba al parámetro
 * real que se manda a Higgsfield.
 *
 * STANDARD (Kling 2.5 Turbo Pro) solo admite duration 5 o 10
 * (DurationPolicy.DiscreteValues, ver KnownHiggsfieldPricing). Una escena con
 * targetDuration real de 7s (fijada vía attachNarrationAudio, no el
 * estimador por palabras) debe llegar a submitGeneration con duration=10, no
 * con el 7 crudo.
 */
class StoryApprovalServiceDurationTest {

    @Test
    void generateApprovedScenes_sendsDurationRoundedUpToAnAllowedValue_notTheRawSceneDuration() {
        RecordingHiggsfieldClient client = new RecordingHiggsfieldClient();
        HiggsfieldConfig config = new HiggsfieldConfig(
                "http://higgsfield.invalid", "key-id", "key-secret",
                Map.of(
                        GenerationTier.STANDARD, KnownHiggsfieldPricing.STANDARD_MODEL_ID,
                        GenerationTier.PREMIUM, KnownHiggsfieldPricing.PREMIUM_MODEL_ID
                ),
                KnownHiggsfieldPricing.defaults());
        StoryApprovalService approvalService = new StoryApprovalService(
                client, config, new DefaultSceneDifficultyScorer(),
                WordsPerSecondDurationEstimator.neutralSpanish(),
                neverCalledTts());

        Scene scene = new Scene("scene-1", SceneRole.GANCHO, 1, "narración", "prompt", Duration.ofSeconds(1));
        scene.proposeTier(new TierRecommendation(GenerationTier.STANDARD, 20, "test"));
        scene.approvePrompt();
        // targetDuration real del audio sintetizado: 7s -- ni 5 ni 10, el
        // caso exacto que Higgsfield rechazaba.
        scene.attachNarrationAudio("audio/scene-1.mp3", Duration.ofSeconds(7));
        Story story = new Story("story-1", "topic", "title", List.of(scene));

        BatchResult estimateResult = approvalService.estimateCostsForApprovedScenes(story);
        assertThat(estimateResult.failedSceneIds()).isEmpty();
        scene.approveCost();

        BatchResult generateResult = approvalService.generateApprovedScenes(story);

        assertThat(generateResult.failedSceneIds()).isEmpty();
        assertThat(generateResult.succeededSceneIds()).containsExactly("scene-1");
        assertThat(client.lastSubmittedDuration).isEqualTo(10L);
    }

    private static TtsService neverCalledTts() {
        return text -> {
            throw new UnsupportedOperationException("No debería llamarse: la escena ya tiene audio adjuntado.");
        };
    }

    /**
     * Fake mínimo que simula el rechazo real de Higgsfield (400
     * "duration: X is not one of [5, 10]") cuando submitGeneration recibe
     * una duración fuera del enum permitido para el modelo.
     */
    private static final class RecordingHiggsfieldClient implements HiggsfieldClient {
        private long lastSubmittedDuration = -1;

        @Override
        public EstimateResponse estimateCost(EstimateRequest request) {
            return new EstimateResponse(BigDecimal.ZERO, "USD", request.modelId(), Map.of());
        }

        @Override
        public GenerationResponse submitGeneration(GenerationRequest request) {
            long duration = ((Number) request.parameters().get("duration")).longValue();
            if (duration != 5L && duration != 10L) {
                throw new HiggsfieldException("duration: " + duration + " is not one of [5, 10]");
            }
            this.lastSubmittedDuration = duration;
            return new GenerationResponse("req-1", "http://higgsfield.invalid/requests/req-1/status", "queued");
        }

        @Override
        public GenerationStatusResponse pollStatus(String statusUrl) {
            throw new UnsupportedOperationException("No debería llamarse en este test.");
        }
    }
}
