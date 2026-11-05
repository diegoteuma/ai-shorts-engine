package com.aishorts.engine.approval;

import com.aishorts.engine.difficulty.DefaultSceneDifficultyScorer;
import com.aishorts.engine.difficulty.TierRecommendation;
import com.aishorts.engine.domain.GenerationStatus;
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
import com.aishorts.engine.higgsfield.KnownHiggsfieldPricing;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Compuerta del GANCHO: generateApprovedScenes con una lista de escenas genera solo esas. */
class StoryApprovalServiceSceneSubsetTest {

    private static final String MODEL = KnownHiggsfieldPricing.WAN_PRIME_MODEL_ID;

    @Test
    void withoutList_generatesEveryApprovedScene_asBefore() {
        CountingClient client = new CountingClient();
        Story story = storyWithApprovedCosts();

        BatchResult result = service(client).generateApprovedScenes(story);

        assertThat(result.succeededSceneIds()).containsExactly("s-gancho", "s-explicacion", "s-contexto");
        assertThat(result.failedSceneIds()).isEmpty();
        assertThat(client.submittedPrompts).containsExactly("s-gancho", "s-explicacion", "s-contexto");
    }

    @Test
    void withList_generatesOnlyThoseScenes_andTheRestStayNotStarted() {
        CountingClient client = new CountingClient();
        Story story = storyWithApprovedCosts();

        BatchResult result = service(client).generateApprovedScenes(story, List.of("s-gancho"));

        assertThat(result.succeededSceneIds()).containsExactly("s-gancho");
        assertThat(result.failedSceneIds()).isEmpty();
        assertThat(client.submittedPrompts).containsExactly("s-gancho");
        assertThat(scene(story, "s-gancho").generationStatus()).isEqualTo(GenerationStatus.QUEUED);
        assertThat(scene(story, "s-explicacion").generationStatus()).isEqualTo(GenerationStatus.NOT_STARTED);
        assertThat(scene(story, "s-contexto").generationStatus()).isEqualTo(GenerationStatus.NOT_STARTED);

        // Después, sin lista, se generan las que faltan (el GANCHO ya está en cola y no se repite).
        BatchResult rest = service(client).generateApprovedScenes(story);
        assertThat(rest.succeededSceneIds()).containsExactly("s-explicacion", "s-contexto");
        assertThat(client.submittedPrompts).containsExactly("s-gancho", "s-explicacion", "s-contexto");
    }

    @Test
    void unknownOrNotReadyScenes_goToFailed_withoutBlockingTheOthers_andWithoutSpending() {
        CountingClient client = new CountingClient();
        Story story = storyWithApprovedCosts();
        StoryApprovalService service = service(client);
        service.generateApprovedScenes(story, List.of("s-gancho"));

        BatchResult result = service.generateApprovedScenes(story, List.of("no-existe", "s-gancho", "s-contexto", "s-contexto"));

        assertThat(result.succeededSceneIds()).containsExactly("s-contexto");
        assertThat(result.failedSceneIds()).containsOnlyKeys("no-existe", "s-gancho");
        assertThat(result.failedSceneIds().get("no-existe")).contains("no tiene una escena con id 'no-existe'");
        assertThat(result.failedSceneIds().get("s-gancho")).contains("no está lista para generar").contains("QUEUED");
        // Una sola llamada nueva (s-contexto, sin duplicar); el GANCHO no se reenvió.
        assertThat(client.submittedPrompts).containsExactly("s-gancho", "s-contexto");
    }

    @Test
    void aSceneWithoutApprovedCost_isNotGenerated() {
        CountingClient client = new CountingClient();
        Scene pending = approvedPrompt("s-gancho", SceneRole.GANCHO, 1);
        Story story = new Story("s", "t", "t", List.of(pending));

        BatchResult result = service(client).generateApprovedScenes(story, List.of("s-gancho"));

        assertThat(result.failedSceneIds().get("s-gancho")).contains("NOT_ESTIMATED");
        assertThat(client.submittedPrompts).isEmpty();
    }

    @Test
    void anEmptyOrNullList_isRejected() {
        Story story = storyWithApprovedCosts();
        StoryApprovalService service = service(new CountingClient());

        assertThatThrownBy(() -> service.generateApprovedScenes(story, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sceneIds no puede estar vacío");
        assertThatThrownBy(() -> service.generateApprovedScenes(story, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- helpers ---------------------------------------------------------------------------

    private static Story storyWithApprovedCosts() {
        List<Scene> scenes = List.of(
                approvedPrompt("s-gancho", SceneRole.GANCHO, 1),
                approvedPrompt("s-explicacion", SceneRole.EXPLICACION, 2),
                approvedPrompt("s-contexto", SceneRole.CONTEXTO, 3));
        for (Scene scene : scenes) {
            scene.recordCostEstimate(new EstimateResponse(new BigDecimal("0.70"), "USD", MODEL, Map.of()), MODEL);
            scene.approveCost();
        }
        return new Story("s", "tema", "título", scenes);
    }

    private static Scene approvedPrompt(String id, SceneRole role, int order) {
        Scene scene = new Scene(id, role, order, "narración", id, Duration.ofSeconds(5));
        scene.proposeTier(new TierRecommendation(GenerationTier.STANDARD, 20, "test"));
        scene.approvePrompt();
        return scene;
    }

    private static Scene scene(Story story, String id) {
        return story.scenes().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    private static StoryApprovalService service(HiggsfieldClient client) {
        HiggsfieldConfig config = new HiggsfieldConfig("http://higgsfield.invalid", "id", "secret",
                Map.of(GenerationTier.STANDARD, MODEL, GenerationTier.PREMIUM, MODEL), KnownHiggsfieldPricing.defaults());
        return new StoryApprovalService(client, config, new DefaultSceneDifficultyScorer(),
                WordsPerSecondDurationEstimator.neutralSpanish(), text -> {
                    throw new UnsupportedOperationException("sin TTS en este test");
                }, 3);
    }

    /** Registra el prompt (el id de la escena) de cada generación enviada. */
    private static final class CountingClient implements HiggsfieldClient {
        final List<String> submittedPrompts = new ArrayList<>();

        @Override
        public EstimateResponse estimateCost(EstimateRequest request) {
            throw new UnsupportedOperationException("No debería llamarse en este test.");
        }

        @Override
        public GenerationResponse submitGeneration(GenerationRequest request) {
            submittedPrompts.add((String) request.parameters().get("prompt"));
            return new GenerationResponse("req-" + submittedPrompts.size(), "http://higgsfield.invalid/r/" + submittedPrompts.size(), "queued");
        }

        @Override
        public GenerationStatusResponse pollStatus(String statusUrl) {
            throw new UnsupportedOperationException("No debería llamarse en este test.");
        }
    }
}
