package com.aishorts.engine.script;

import com.aishorts.engine.domain.Scene;
import com.aishorts.engine.domain.Story;
import com.aishorts.engine.duration.NarrationDurationEstimator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Convierte un StoryBrief (lo que vos ya aprobaste) en un Story con sus 6
 * Scene ya armadas y con duración estimada, listo para
 * StoryApprovalService.proposeTiersForReview y la puerta 1. No aprueba nada
 * — solo arma el borrador.
 */
public final class StoryDraftingService {

    private final ScriptDraftingService scriptDraftingService;
    private final NarrationDurationEstimator durationEstimator;

    public StoryDraftingService(ScriptDraftingService scriptDraftingService, NarrationDurationEstimator durationEstimator) {
        this.scriptDraftingService = scriptDraftingService;
        this.durationEstimator = durationEstimator;
    }

    public Story draftStory(String storyId, StoryBrief brief) {
        Objects.requireNonNull(storyId, "storyId");
        List<SceneDraft> drafts = scriptDraftingService.draftScenes(brief);

        List<Scene> scenes = new ArrayList<>();
        int order = 1;
        for (SceneDraft draft : drafts) {
            String sceneId = storyId + "-" + draft.role().name().toLowerCase();
            scenes.add(new Scene(
                    sceneId,
                    draft.role(),
                    order++,
                    draft.narrationText(),
                    draft.visualPrompt(),
                    durationEstimator.estimate(draft.narrationText())
            ));
        }
        return new Story(storyId, brief.topic(), brief.title(), scenes);
    }
}
