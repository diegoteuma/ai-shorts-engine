package com.aishorts.engine.domain;

import com.aishorts.engine.duration.DurationBudget;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Un Short completo (el "microdocumental"), con sus escenas en orden narrativo.
 * El estado de la historia se deriva del estado de sus escenas: no hay un
 * setter de estado suelto, así se evita que algo quede en un estado agregado
 * que no corresponde con lo que realmente pasó escena por escena.
 */
public final class Story {

    private final String id;
    private final String topic;
    private final String title;
    private final List<Scene> scenes;

    public Story(String id, String topic, String title, List<Scene> scenes) {
        this.id = Objects.requireNonNull(id, "id");
        this.topic = Objects.requireNonNull(topic, "topic");
        this.title = Objects.requireNonNull(title, "title");
        if (scenes == null || scenes.isEmpty()) {
            throw new IllegalArgumentException("Una historia necesita al menos una escena.");
        }
        this.scenes = scenes.stream()
                .sorted(Comparator.comparingInt(Scene::order))
                .collect(Collectors.toUnmodifiableList());
    }

    public String id() { return id; }
    public String topic() { return topic; }
    public String title() { return title; }
    public List<Scene> scenes() { return scenes; }

    // --- presupuesto de duración ---------------------------------------------------------

    /** Suma de la duración estimada de las 6 escenas, recalculada en cada consulta. */
    public Duration totalTargetDuration() {
        return scenes.stream().map(Scene::targetDuration).reduce(Duration.ZERO, Duration::plus);
    }

    public boolean isWithinDurationBudget(DurationBudget budget) {
        return budget.contains(totalTargetDuration());
    }

    // --- lote de prompts ---------------------------------------------------------------

    public boolean allPromptsDecided() {
        return scenes.stream().allMatch(s -> s.promptStatus() != SceneApprovalStatus.PENDING);
    }

    public List<Scene> scenesPendingPromptDecision() {
        return scenes.stream()
                .filter(s -> s.promptStatus() == SceneApprovalStatus.PENDING)
                .collect(Collectors.toUnmodifiableList());
    }

    // --- lote de costos ------------------------------------------------------------------

    public List<Scene> scenesNeedingCostEstimate() {
        return scenes.stream().filter(Scene::needsCostEstimate).collect(Collectors.toUnmodifiableList());
    }

    public boolean allCostsDecided() {
        return scenes.stream()
                .filter(Scene::isPromptApproved)
                .allMatch(s -> s.costStatus() == SceneCostStatus.APPROVED
                        || s.costStatus() == SceneCostStatus.REJECTED);
    }

    public BigDecimal totalEstimatedCost() {
        return scenes.stream()
                .filter(s -> s.costEstimate() != null)
                .map(s -> s.costEstimate().cost())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // --- generación --------------------------------------------------------------------

    public List<Scene> scenesReadyToGenerate() {
        return scenes.stream().filter(Scene::isReadyToGenerate).collect(Collectors.toUnmodifiableList());
    }

    public boolean allGenerationFinished() {
        return scenes.stream()
                .filter(s -> s.isCostApproved())
                .allMatch(s -> s.generationStatus() == GenerationStatus.COMPLETED
                        || s.generationStatus() == GenerationStatus.FAILED);
    }

    /**
     * Estado agregado derivado, recalculado en cada consulta a partir del
     * estado real de las escenas (nunca hay drift entre el estado de la
     * historia y el de sus partes).
     */
    public StoryStatus status() {
        boolean anyGenerationStarted = scenes.stream()
                .anyMatch(s -> s.generationStatus() != GenerationStatus.NOT_STARTED);
        if (anyGenerationStarted) {
            return allGenerationFinished() ? StoryStatus.GENERATED : StoryStatus.GENERATING;
        }

        boolean anyCostDecided = scenes.stream()
                .anyMatch(s -> s.costStatus() == SceneCostStatus.APPROVED || s.costStatus() == SceneCostStatus.REJECTED);
        if (allCostsDecided() && anyCostDecided) {
            return StoryStatus.COSTS_APPROVED;
        }

        boolean anyCostEstimated = scenes.stream().anyMatch(s -> s.costEstimate() != null);
        if (anyCostEstimated) {
            return StoryStatus.COSTS_PENDING_REVIEW;
        }

        if (allPromptsDecided()) {
            return StoryStatus.PROMPTS_APPROVED;
        }

        boolean anyPromptDecided = scenes.stream()
                .anyMatch(s -> s.promptStatus() != SceneApprovalStatus.PENDING);
        boolean anyTierProposed = scenes.stream().anyMatch(s -> s.tierRecommendation() != null);
        if (anyPromptDecided || anyTierProposed) {
            return StoryStatus.PROMPTS_PENDING_REVIEW;
        }

        return StoryStatus.DRAFT;
    }

    // --- persistencia (snapshot) ---------------------------------------------------------

    /** Captura TODO el estado actual (la historia y sus escenas), para guardarlo. */
    public StorySnapshot toSnapshot() {
        return new StorySnapshot(id, topic, title,
                scenes.stream().map(Scene::toSnapshot).collect(Collectors.toUnmodifiableList()));
    }

    /** Reconstruye una Story exactamente en el estado del snapshot. Ver {@link Scene#fromSnapshot}. */
    public static Story fromSnapshot(StorySnapshot snapshot) {
        List<Scene> scenes = snapshot.scenes().stream()
                .map(Scene::fromSnapshot)
                .collect(Collectors.toUnmodifiableList());
        return new Story(snapshot.id(), snapshot.topic(), snapshot.title(), scenes);
    }

    @Override
    public String toString() {
        return "Story[" + title + " (" + topic + ") status=" + status() + " escenas=" + scenes.size() + "]";
    }
}
