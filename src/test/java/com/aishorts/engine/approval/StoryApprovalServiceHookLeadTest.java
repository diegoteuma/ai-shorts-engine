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
import com.aishorts.engine.higgsfield.HiggsfieldRestClient;
import com.aishorts.engine.higgsfield.KnownHiggsfieldPricing;
import com.aishorts.engine.tts.TtsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Margen de arranque del GANCHO (engine.hook.lead-seconds): solo esa escena
 * pide ceil(targetDuration + lead) segundos a Higgsfield, el costo de la
 * PUERTA 2 los incluye y el clip queda con clipStartOffset = lead. El costo
 * sale de la tabla local real (HiggsfieldRestClient.estimateCost no usa red).
 */
class StoryApprovalServiceHookLeadTest {

    private static final double LEAD = 3;

    /** Wan 3.0 Prime: 0,14 USD/s (tarifa sin verificar), duración continua 2-30 s. */
    private final HiggsfieldConfig wanConfig = config(KnownHiggsfieldPricing.WAN_PRIME_MODEL_ID);
    /** Seedance 2.0: duración continua 4-15 s. */
    private final HiggsfieldConfig seedanceConfig = config(KnownHiggsfieldPricing.STANDARD_MODEL_ID);

    @Test
    void onlyTheHookAsksForTheLeadSeconds_andItsCostIncludesThem() {
        RecordingClient client = new RecordingClient(wanConfig);
        Scene hook = approvedScene("s-gancho", SceneRole.GANCHO, 1, Duration.ofMillis(4458));
        Scene explanation = approvedScene("s-explicacion", SceneRole.EXPLICACION, 2, Duration.ofMillis(6200));
        Story story = new Story("s", "tema", "título", List.of(hook, explanation));

        assertThat(service(client, wanConfig, LEAD).estimateCostsForApprovedScenes(story).failedSceneIds()).isEmpty();

        // GANCHO: ceil(4.458 + 3) = 8 s; EXPLICACION sin margen: ceil(6.2) = 7 s.
        assertThat(client.estimatedDurations).containsExactly(Map.entry("s-gancho", 8L), Map.entry("s-explicacion", 7L));
        assertThat(hook.costEstimate().cost()).isEqualByComparingTo("1.12");
        assertThat(explanation.costEstimate().cost()).isEqualByComparingTo("0.98");
        assertThat(story.totalEstimatedCost()).isEqualByComparingTo("2.10");
    }

    @Test
    void theLeadAddsLeadTimesTheRateToTheHookCost() {
        Scene withLead = approvedScene("a-gancho", SceneRole.GANCHO, 1, Duration.ofMillis(4458));
        Scene withoutLead = approvedScene("b-gancho", SceneRole.GANCHO, 1, Duration.ofMillis(4458));
        service(new RecordingClient(wanConfig), wanConfig, LEAD)
                .estimateCostsForApprovedScenes(new Story("a", "t", "t", List.of(withLead)));
        service(new RecordingClient(wanConfig), wanConfig, 0)
                .estimateCostsForApprovedScenes(new Story("b", "t", "t", List.of(withoutLead)));

        // 8 s vs 5 s a 0,14 USD/s: +0,42 USD.
        assertThat(withLead.costEstimate().cost().subtract(withoutLead.costEstimate().cost())).isEqualByComparingTo("0.42");
    }

    @Test
    void generate_sendsTheSameDurationsAsTheEstimate_andSetsTheDefaultClipStartOffset() {
        RecordingClient client = new RecordingClient(wanConfig);
        Scene hook = approvedScene("s-gancho", SceneRole.GANCHO, 1, Duration.ofMillis(4458));
        Scene explanation = approvedScene("s-explicacion", SceneRole.EXPLICACION, 2, Duration.ofMillis(6200));
        Story story = new Story("s", "tema", "título", List.of(hook, explanation));
        StoryApprovalService service = service(client, wanConfig, LEAD);
        service.estimateCostsForApprovedScenes(story);
        hook.approveCost();
        explanation.approveCost();

        assertThat(service.generateApprovedScenes(story).failedSceneIds()).isEmpty();

        assertThat(client.submittedDurations).isEqualTo(client.estimatedDurations);
        assertThat(hook.clipStartOffset()).isEqualTo(3.0);
        assertThat(explanation.clipStartOffset()).isEqualTo(0.0);
    }

    @Test
    void aNarrationChangeDiscardsTheCost_andTheNewEstimateStillIncludesTheLead() {
        RecordingClient client = new RecordingClient(wanConfig);
        Scene hook = approvedScene("s-gancho", SceneRole.GANCHO, 1, Duration.ofMillis(4458));
        Story story = new Story("s", "tema", "título", List.of(hook));
        StoryApprovalService service = service(client, wanConfig, LEAD);
        service.estimateCostsForApprovedScenes(story);
        assertThat(hook.costEstimate().cost()).isEqualByComparingTo("1.12");

        hook.updateNarration("Otra narración más larga para el gancho.", Duration.ofMillis(5200));
        assertThat(hook.costEstimate()).isNull();
        service.estimateCostsForApprovedScenes(story);

        // ceil(5.2 + 3) = 9 s a 0,14 USD/s.
        assertThat(client.estimatedDurations.get("s-gancho")).isEqualTo(9L);
        assertThat(hook.costEstimate().cost()).isEqualByComparingTo("1.26");
    }

    @Test
    void theLeadRespectsTheModelDurationPolicy() {
        // Seedance admite hasta 15 s: un GANCHO de 13 s cabe sin margen pero no con 3 s más.
        Scene longHook = approvedScene("s-gancho", SceneRole.GANCHO, 1, Duration.ofSeconds(13));
        BatchResult withLead = service(new RecordingClient(seedanceConfig), seedanceConfig, LEAD)
                .estimateCostsForApprovedScenes(new Story("s", "t", "t", List.of(longHook)));
        assertThat(withLead.failedSceneIds()).containsKey("s-gancho");
        assertThat(withLead.failedSceneIds().get("s-gancho")).contains("16s").contains("15s");

        // Y por abajo redondea al mínimo del modelo: ceil(0.4 + 0.2) = 1 s -> 4 s en Seedance.
        RecordingClient client = new RecordingClient(seedanceConfig);
        Scene shortHook = approvedScene("t-gancho", SceneRole.GANCHO, 1, Duration.ofMillis(400));
        service(client, seedanceConfig, 0.2).estimateCostsForApprovedScenes(new Story("t", "t", "t", List.of(shortHook)));
        assertThat(client.estimatedDurations.get("t-gancho")).isEqualTo(4L);
    }

    @Test
    void resetForRegeneration_putsTheClipStartOffsetBackToZero() {
        RecordingClient client = new RecordingClient(wanConfig);
        Scene hook = approvedScene("s-gancho", SceneRole.GANCHO, 1, Duration.ofMillis(4458));
        Story story = new Story("s", "tema", "título", List.of(hook));
        StoryApprovalService service = service(client, wanConfig, LEAD);
        service.estimateCostsForApprovedScenes(story);
        hook.approveCost();
        service.generateApprovedScenes(story);
        hook.failGeneration("falló");
        assertThat(hook.clipStartOffset()).isEqualTo(3.0);

        assertThat(service.resetAllScenesForRegeneration(story).failedSceneIds()).isEmpty();

        assertThat(hook.clipStartOffset()).isEqualTo(0.0);
    }

    @Test
    void aNegativeOrNonFiniteLeadIsRejectedAtConstruction() {
        RecordingClient client = new RecordingClient(wanConfig);
        assertThatThrownBy(() -> service(client, wanConfig, -1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lead-seconds");
        assertThatThrownBy(() -> service(client, wanConfig, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theClipStartOffsetMustBeFiniteAndNotNegative() {
        Scene scene = new Scene("s-gancho", SceneRole.GANCHO, 1, "n", "p", Duration.ofSeconds(4));
        assertThatThrownBy(() -> scene.setClipStartOffset(-0.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scene.setClipStartOffset(Double.POSITIVE_INFINITY)).isInstanceOf(IllegalArgumentException.class);
        assertThat(scene.clipStartOffset()).isEqualTo(0.0);
        scene.setClipStartOffset(1.5);
        assertThat(scene.clipStartOffset()).isEqualTo(1.5);
    }

    // --- helpers ---------------------------------------------------------------------------

    private static HiggsfieldConfig config(String modelId) {
        return new HiggsfieldConfig("http://higgsfield.invalid", "key-id", "key-secret",
                Map.of(GenerationTier.STANDARD, modelId, GenerationTier.PREMIUM, modelId),
                KnownHiggsfieldPricing.defaults());
    }

    private static StoryApprovalService service(HiggsfieldClient client, HiggsfieldConfig config, double lead) {
        return new StoryApprovalService(client, config, new DefaultSceneDifficultyScorer(),
                WordsPerSecondDurationEstimator.neutralSpanish(), neverCalledTts(), lead);
    }

    /** Escena con prompt aprobado (su id como visualPrompt) y duración real de audio (la que usan estimate y generate). */
    private static Scene approvedScene(String id, SceneRole role, int order, Duration audioDuration) {
        Scene scene = new Scene(id, role, order, "narración", id, Duration.ofSeconds(1));
        scene.proposeTier(new TierRecommendation(GenerationTier.STANDARD, 20, "test"));
        scene.approvePrompt();
        scene.attachNarrationAudio("audio/" + id + ".mp3", audioDuration);
        return scene;
    }

    private static TtsService neverCalledTts() {
        return text -> {
            throw new UnsupportedOperationException("No debería llamarse: las escenas ya tienen audio.");
        };
    }

    /** Estima con la tabla local real y registra la duración pedida por escena. */
    private static final class RecordingClient implements HiggsfieldClient {
        private final HiggsfieldRestClient realEstimate;
        private final Map<String, Long> estimatedDurations = new LinkedHashMap<>();
        private final Map<String, Long> submittedDurations = new LinkedHashMap<>();
        private int requests;

        RecordingClient(HiggsfieldConfig config) {
            this.realEstimate = new HiggsfieldRestClient(config, new ObjectMapper());
        }

        @Override
        public EstimateResponse estimateCost(EstimateRequest request) {
            estimatedDurations.put(sceneIdOf(request.parameters()), duration(request.parameters()));
            return realEstimate.estimateCost(request);
        }

        @Override
        public GenerationResponse submitGeneration(GenerationRequest request) {
            submittedDurations.put(sceneIdOf(request.parameters()), duration(request.parameters()));
            requests++;
            return new GenerationResponse("req-" + requests, "http://higgsfield.invalid/requests/" + requests, "queued");
        }

        @Override
        public GenerationStatusResponse pollStatus(String statusUrl) {
            throw new UnsupportedOperationException("No debería llamarse en este test.");
        }

        private static long duration(Map<String, Object> parameters) {
            return ((Number) parameters.get("duration")).longValue();
        }

        /** approvedScene usa el id de la escena como visualPrompt. */
        private static String sceneIdOf(Map<String, Object> parameters) {
            return (String) parameters.get("prompt");
        }
    }
}
