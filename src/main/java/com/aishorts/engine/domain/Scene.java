package com.aishorts.engine.domain;

import com.aishorts.engine.difficulty.TierRecommendation;
import com.aishorts.engine.higgsfield.EstimateResponse;

import java.time.Duration;
import java.util.Objects;

/**
 * Una escena del Short (gancho, explicación, contexto, giro, consecuencia o cierre).
 *
 * El objeto es intencionalmente el guardián de su propio ciclo de vida: los
 * métodos de transición (approvePrompt, estimateCost, approveCost,
 * startGeneration, ...) lanzan IllegalStateException si se invocan fuera de
 * orden. Así la regla "nunca generar sin aprobación" no depende de que el
 * código que orquesta el flujo se acuerde de chequearla en cada lugar: la
 * propia escena la hace cumplir.
 */
public final class Scene {

    private final String id;
    private final SceneRole role;
    private final int order;

    private String narrationText;
    private String visualPrompt;
    private Duration targetDuration;

    private TierRecommendation tierRecommendation;
    private GenerationTier chosenTier;
    private String chosenModelId;

    private SceneApprovalStatus promptStatus = SceneApprovalStatus.PENDING;
    private String promptRejectionNote;

    private SceneCostStatus costStatus = SceneCostStatus.NOT_ESTIMATED;
    private EstimateResponse costEstimate;
    private String costRejectionNote;

    private String narrationAudioPath;

    private GenerationStatus generationStatus = GenerationStatus.NOT_STARTED;
    private String higgsfieldRequestId;
    private String higgsfieldStatusUrl;
    private String generatedAssetUrl;
    private String generationFailureReason;

    public Scene(String id, SceneRole role, int order, String narrationText, String visualPrompt, Duration targetDuration) {
        this.id = Objects.requireNonNull(id, "id");
        this.role = Objects.requireNonNull(role, "role");
        this.order = order;
        this.narrationText = Objects.requireNonNull(narrationText, "narrationText");
        this.visualPrompt = Objects.requireNonNull(visualPrompt, "visualPrompt");
        this.targetDuration = Objects.requireNonNull(targetDuration, "targetDuration");
    }

    /**
     * Actualiza el texto de narración y su duración estimada juntos, para
     * que nunca queden desincronizados. La estimación en sí la calcula
     * quien llama (normalmente vía NarrationDurationEstimator) — Scene no
     * conoce el algoritmo de estimación, igual que no conoce el de scoring
     * de dificultad.
     */
    public void updateNarration(String narrationText, Duration targetDuration) {
        this.narrationText = Objects.requireNonNull(narrationText, "narrationText");
        this.targetDuration = Objects.requireNonNull(targetDuration, "targetDuration");
    }

    // --- Puerta 1: propuesta y aprobación de prompt -------------------------------------

    public void proposeTier(TierRecommendation recommendation) {
        this.tierRecommendation = recommendation;
        this.chosenTier = recommendation.tier();
    }

    public void approvePrompt() {
        approvePrompt(null, null);
    }

    /**
     * Aprueba el prompt de la escena, opcionalmente con un texto revisado y/o
     * una anulación manual del tier sugerido (por si vos decidís que una
     * escena "de transición" igual merece premium, o al revés).
     */
    public void approvePrompt(String revisedVisualPrompt, GenerationTier tierOverride) {
        if (promptStatus == SceneApprovalStatus.APPROVED) {
            return;
        }
        if (revisedVisualPrompt != null && !revisedVisualPrompt.isBlank()) {
            this.visualPrompt = revisedVisualPrompt;
        }
        if (tierOverride != null) {
            this.chosenTier = tierOverride;
        }
        if (this.chosenTier == null) {
            throw new IllegalStateException(
                    "La escena '" + id + "' no tiene un tier sugerido ni uno manual; "
                            + "llamá a proposeTier(...) antes de aprobar el prompt.");
        }
        this.promptStatus = SceneApprovalStatus.APPROVED;
        this.promptRejectionNote = null;
    }

    public void rejectPrompt(String note) {
        this.promptStatus = SceneApprovalStatus.REJECTED;
        this.promptRejectionNote = note;
    }

    public boolean isPromptApproved() {
        return promptStatus == SceneApprovalStatus.APPROVED;
    }

    // --- Síntesis de voz (entre puerta 1 y puerta 2) --------------------------------------

    /**
     * Solo se puede adjuntar audio si el prompt ya fue aprobado (la síntesis
     * es contenido pago, aunque sea de centavos). Reemplaza targetDuration
     * por la duración real del audio sintetizado — deja de ser una
     * estimación por conteo de palabras a partir de acá. No toca
     * narrationText: el texto no cambia, solo se conoce mejor cuánto dura.
     */
    public void attachNarrationAudio(String audioPath, Duration actualDuration) {
        requirePromptApproved("adjuntar audio de narración");
        this.narrationAudioPath = Objects.requireNonNull(audioPath, "audioPath");
        this.targetDuration = Objects.requireNonNull(actualDuration, "actualDuration");
    }

    public boolean hasNarrationAudio() {
        return narrationAudioPath != null;
    }

    // --- Puerta 2: estimación y aprobación de costo --------------------------------------

    /** El costo solo puede registrarse si el prompt de esta escena ya fue aprobado. */
    public void recordCostEstimate(EstimateResponse estimate, String modelId) {
        requirePromptApproved("registrar un costo estimado");
        this.costEstimate = Objects.requireNonNull(estimate, "estimate");
        this.chosenModelId = modelId;
        this.costStatus = SceneCostStatus.ESTIMATED;
    }

    public void approveCost() {
        if (costStatus != SceneCostStatus.ESTIMATED) {
            throw new IllegalStateException(
                    "La escena '" + id + "' no tiene un costo estimado para aprobar (estado actual: "
                            + costStatus + ").");
        }
        this.costStatus = SceneCostStatus.APPROVED;
        this.costRejectionNote = null;
    }

    public void rejectCost(String note) {
        if (costStatus != SceneCostStatus.ESTIMATED) {
            throw new IllegalStateException(
                    "La escena '" + id + "' no tiene un costo estimado para rechazar (estado actual: "
                            + costStatus + ").");
        }
        this.costStatus = SceneCostStatus.REJECTED;
        this.costRejectionNote = note;
    }

    public boolean isCostApproved() {
        return costStatus == SceneCostStatus.APPROVED;
    }

    public boolean needsCostEstimate() {
        return isPromptApproved() && costStatus == SceneCostStatus.NOT_ESTIMATED;
    }

    // --- Generación real -------------------------------------------------------------------

    /**
     * Solo se puede invocar si el costo de la escena ya fue APPROVED. Nunca
     * es automático. statusUrl se guarda tal cual la devuelve Higgsfield
     * para hacer polling después (ver {@link com.aishorts.engine.higgsfield.HiggsfieldClient#pollStatus}).
     */
    public void startGeneration(String higgsfieldRequestId, String statusUrl) {
        requireCostApproved("iniciar la generación");
        this.higgsfieldRequestId = Objects.requireNonNull(higgsfieldRequestId, "higgsfieldRequestId");
        this.higgsfieldStatusUrl = statusUrl;
        this.generationStatus = GenerationStatus.QUEUED;
    }

    public void markInProgress() {
        requireQueuedOrInProgress();
        this.generationStatus = GenerationStatus.IN_PROGRESS;
    }

    public void completeGeneration(String assetUrl) {
        this.generatedAssetUrl = Objects.requireNonNull(assetUrl, "assetUrl");
        this.generationStatus = GenerationStatus.COMPLETED;
    }

    public void failGeneration(String reason) {
        this.generationFailureReason = reason;
        this.generationStatus = GenerationStatus.FAILED;
    }

    public boolean isReadyToGenerate() {
        return isCostApproved() && generationStatus == GenerationStatus.NOT_STARTED;
    }

    // --- guards ------------------------------------------------------------------------

    private void requirePromptApproved(String action) {
        if (!isPromptApproved()) {
            throw new IllegalStateException(
                    "No se puede " + action + " para la escena '" + id
                            + "': el prompt todavía no fue aprobado (estado: " + promptStatus + ").");
        }
    }

    private void requireCostApproved(String action) {
        if (!isCostApproved()) {
            throw new IllegalStateException(
                    "No se puede " + action + " para la escena '" + id
                            + "': el costo todavía no fue aprobado (estado: " + costStatus + ").");
        }
    }

    private void requireQueuedOrInProgress() {
        if (generationStatus != GenerationStatus.QUEUED && generationStatus != GenerationStatus.IN_PROGRESS) {
            throw new IllegalStateException(
                    "La escena '" + id + "' no está en cola ni en progreso (estado: " + generationStatus + ").");
        }
    }

    // --- persistencia (snapshot) ----------------------------------------------------------

    /** Captura TODO el estado actual, para guardarlo (ver {@link com.aishorts.engine.persistence.StoryRepository}). */
    public SceneSnapshot toSnapshot() {
        return new SceneSnapshot(
                id, role, order, narrationText, visualPrompt, targetDuration,
                tierRecommendation, chosenTier, chosenModelId,
                promptStatus, promptRejectionNote,
                costStatus, costEstimate, costRejectionNote,
                narrationAudioPath,
                generationStatus, higgsfieldRequestId, higgsfieldStatusUrl,
                generatedAssetUrl, generationFailureReason);
    }

    /**
     * Reconstruye una Scene en exactamente el estado que tenía cuando se
     * tomó el snapshot, sin pasar por approvePrompt/recordCostEstimate/etc.:
     * ese estado ya fue válido una vez (lo hicieron cumplir esos mismos
     * guards en la ejecución que lo generó), así que esto es leer, no una
     * transición nueva.
     */
    public static Scene fromSnapshot(SceneSnapshot snapshot) {
        Scene scene = new Scene(
                snapshot.id(), snapshot.role(), snapshot.order(),
                snapshot.narrationText(), snapshot.visualPrompt(), snapshot.targetDuration());
        scene.tierRecommendation = snapshot.tierRecommendation();
        scene.chosenTier = snapshot.chosenTier();
        scene.chosenModelId = snapshot.chosenModelId();
        scene.promptStatus = snapshot.promptStatus();
        scene.promptRejectionNote = snapshot.promptRejectionNote();
        scene.costStatus = snapshot.costStatus();
        scene.costEstimate = snapshot.costEstimate();
        scene.costRejectionNote = snapshot.costRejectionNote();
        scene.narrationAudioPath = snapshot.narrationAudioPath();
        scene.generationStatus = snapshot.generationStatus();
        scene.higgsfieldRequestId = snapshot.higgsfieldRequestId();
        scene.higgsfieldStatusUrl = snapshot.higgsfieldStatusUrl();
        scene.generatedAssetUrl = snapshot.generatedAssetUrl();
        scene.generationFailureReason = snapshot.generationFailureReason();
        return scene;
    }

    // --- getters -------------------------------------------------------------------------

    public String id() { return id; }
    public SceneRole role() { return role; }
    public int order() { return order; }
    public String narrationText() { return narrationText; }
    public String visualPrompt() { return visualPrompt; }
    public Duration targetDuration() { return targetDuration; }
    public String narrationAudioPath() { return narrationAudioPath; }
    public TierRecommendation tierRecommendation() { return tierRecommendation; }
    public GenerationTier chosenTier() { return chosenTier; }
    public String chosenModelId() { return chosenModelId; }
    public SceneApprovalStatus promptStatus() { return promptStatus; }
    public String promptRejectionNote() { return promptRejectionNote; }
    public SceneCostStatus costStatus() { return costStatus; }
    public EstimateResponse costEstimate() { return costEstimate; }
    public String costRejectionNote() { return costRejectionNote; }
    public GenerationStatus generationStatus() { return generationStatus; }
    public String higgsfieldRequestId() { return higgsfieldRequestId; }
    public String higgsfieldStatusUrl() { return higgsfieldStatusUrl; }
    public String generatedAssetUrl() { return generatedAssetUrl; }
    public String generationFailureReason() { return generationFailureReason; }

    @Override
    public String toString() {
        return "Scene[" + order + " " + role + " id=" + id + " prompt=" + promptStatus
                + " cost=" + costStatus + " gen=" + generationStatus + "]";
    }
}
