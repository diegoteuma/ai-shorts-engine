package com.aishorts.engine.domain;

import com.aishorts.engine.difficulty.TierRecommendation;
import com.aishorts.engine.duration.DurationBudget;
import com.aishorts.engine.higgsfield.EstimateResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lógica propia de Story además de agregar Scene: el presupuesto de
 * duración total (35-40s) y el estado agregado, que se deriva del estado
 * real de las escenas y nunca puede quedar "adelantado" respecto a ellas.
 */
class StoryTest {

    private static final SceneRole[] ROLES = {
            SceneRole.GANCHO, SceneRole.EXPLICACION, SceneRole.CONTEXTO,
            SceneRole.GIRO, SceneRole.CONSECUENCIA, SceneRole.CIERRE
    };

    private static Story sixSceneStory(int... secondsPerScene) {
        List<Scene> scenes = new ArrayList<>();
        for (int i = 0; i < ROLES.length; i++) {
            scenes.add(new Scene(
                    "scene-" + (i + 1), ROLES[i], i + 1,
                    "narración " + (i + 1), "prompt " + (i + 1), Duration.ofSeconds(secondsPerScene[i])));
        }
        return new Story("story-1", "topic", "title", scenes);
    }

    private static EstimateResponse estimate(String cost) {
        return new EstimateResponse(new BigDecimal(cost), "USD", "model", Map.of());
    }

    @Test
    void totalTargetDurationSumsAllScenes() {
        Story story = sixSceneStory(5, 8, 6, 4, 8, 4);
        assertThat(story.totalTargetDuration()).isEqualTo(Duration.ofSeconds(35));
    }

    @Test
    void isWithinDurationBudgetRespectsInclusiveBounds() {
        DurationBudget budget = DurationBudget.ofSeconds(35, 40);

        assertThat(sixSceneStory(5, 8, 6, 4, 8, 4).isWithinDurationBudget(budget)).isTrue();  // 35s, límite inferior
        assertThat(sixSceneStory(5, 8, 6, 4, 8, 9).isWithinDurationBudget(budget)).isTrue();  // 40s, límite superior
        assertThat(sixSceneStory(5, 8, 6, 4, 8, 3).isWithinDurationBudget(budget)).isFalse(); // 34s, por debajo
        assertThat(sixSceneStory(5, 8, 6, 4, 8, 10).isWithinDurationBudget(budget)).isFalse(); // 41s, por encima
    }

    @Test
    void statusStartsAsDraftAndAdvancesWithPromptDecisions() {
        Story story = sixSceneStory(5, 8, 6, 4, 8, 4);
        assertThat(story.status()).isEqualTo(StoryStatus.DRAFT);

        for (Scene s : story.scenes()) {
            s.proposeTier(new TierRecommendation(GenerationTier.STANDARD, 30, "test"));
        }
        assertThat(story.status()).isEqualTo(StoryStatus.PROMPTS_PENDING_REVIEW);

        for (Scene s : story.scenes()) {
            s.approvePrompt();
        }
        assertThat(story.status()).isEqualTo(StoryStatus.PROMPTS_APPROVED);
    }

    @Test
    void aRejectedSceneDoesNotBlockTheRestOfTheBatch() {
        Story story = sixSceneStory(5, 8, 6, 4, 8, 4);
        for (Scene s : story.scenes()) {
            s.proposeTier(new TierRecommendation(GenerationTier.STANDARD, 30, "test"));
        }

        List<Scene> scenes = story.scenes();
        scenes.get(0).rejectPrompt("no me convence");
        for (int i = 1; i < scenes.size(); i++) {
            scenes.get(i).approvePrompt();
        }

        // El lote completo quedó decidido (una rechazada, cinco aprobadas) —
        // el rechazo de una escena no frena a las demás ni al avance del status agregado.
        assertThat(story.allPromptsDecided()).isTrue();
        assertThat(story.status()).isEqualTo(StoryStatus.PROMPTS_APPROVED);
        assertThat(scenes.get(0).promptStatus()).isEqualTo(SceneApprovalStatus.REJECTED);
    }

    @Test
    void totalEstimatedCostSumsOnlyScenesWithACostEstimate() {
        Story story = sixSceneStory(5, 8, 6, 4, 8, 4);
        List<Scene> scenes = story.scenes();

        for (Scene s : scenes) {
            s.proposeTier(new TierRecommendation(GenerationTier.STANDARD, 30, "test"));
            s.approvePrompt();
        }
        scenes.get(0).recordCostEstimate(estimate("1.20"), "model");
        scenes.get(1).recordCostEstimate(estimate("0.35"), "model");
        // El resto de las escenas se deja sin estimar a propósito.

        assertThat(story.totalEstimatedCost()).isEqualByComparingTo(new BigDecimal("1.55"));
    }
}
