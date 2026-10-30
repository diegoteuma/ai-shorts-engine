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

    /**
     * Copia local del clip generado, como ruta RELATIVA al directorio de clips
     * ({@code engine.clips-dir}), p. ej. {@code tunguska-gancho.mp4}. Null si
     * todavía no se descargó o falló. Relativa a propósito: portable entre
     * máquinas (a diferencia de narrationAudioPath, que es absoluta).
     */
    private String localClipPath;
    /** Motivo de la última descarga fallida del clip; null si no hubo fallo o ya se resolvió. */
    private String localClipFailureReason;
    /**
     * Desde qué segundo del clip se empieza a mostrar (0 = desde el inicio).
     * En el GANCHO vale por defecto el margen de arranque pedido de más a
     * Higgsfield (engine.hook.lead-seconds), para saltear la construcción
     * lenta del modelo; se ajusta con POST /stories/{id}/clip-offsets.
     */
    private double clipStartOffset;

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
     *
     * Si el texto cambia, el audio ya sintetizado (si lo había) deja de
     * corresponderle: se descarta para que /narration lo vuelva a sintetizar.
     * También se descarta el costo (estimado o aprobado): se calculó con la
     * duración anterior y /generate pide el clip con la nueva. Como en
     * approvePrompt, se rechaza si la generación ya arrancó.
     */
    public void updateNarration(String narrationText, Duration targetDuration) {
        Objects.requireNonNull(narrationText, "narrationText");
        Objects.requireNonNull(targetDuration, "targetDuration");
        if (!narrationText.equals(this.narrationText)) {
            if (generationStatus != GenerationStatus.NOT_STARTED) {
                throw new IllegalStateException(
                        "La escena '" + id + "' ya tiene generación (estado: " + generationStatus
                                + "); para revisar su narración primero usá reset-generation.");
            }
            this.narrationAudioPath = null;
            discardCost();
        }
        this.narrationText = narrationText;
        this.targetDuration = targetDuration;
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
     *
     * Sobre una escena ya aprobada: sin cambios es un no-op (idempotente);
     * con un prompt o tier distinto se aplica la revisión y se descarta el
     * costo estimado/aprobado, porque era del prompt/tier anterior (hay que
     * volver a pasar por cost-estimates y la PUERTA 2). Si la generación ya
     * arrancó se rechaza: primero reset-generation.
     */
    public void approvePrompt(String revisedVisualPrompt, GenerationTier tierOverride) {
        if (promptStatus == SceneApprovalStatus.APPROVED) {
            boolean promptChanges = revisedVisualPrompt != null && !revisedVisualPrompt.isBlank()
                    && !revisedVisualPrompt.equals(visualPrompt);
            boolean tierChanges = tierOverride != null && tierOverride != chosenTier;
            if (!promptChanges && !tierChanges) {
                return;
            }
            if (generationStatus != GenerationStatus.NOT_STARTED) {
                throw new IllegalStateException(
                        "La escena '" + id + "' ya tiene generación (estado: " + generationStatus
                                + "); para revisar su prompt o tier primero usá reset-generation.");
            }
            discardCost();
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

    /** Registra que el clip ya está guardado localmente. Solo tiene sentido con la generación COMPLETED. */
    public void attachLocalClip(String relativePath) {
        requireCompleted("adjuntar el clip local");
        this.localClipPath = Objects.requireNonNull(relativePath, "relativePath");
        this.localClipFailureReason = null;
    }

    /** Registra por qué falló la descarga del clip (no cambia generationStatus: la generación sí terminó). */
    public void recordClipDownloadFailure(String reason) {
        requireCompleted("registrar un fallo de descarga del clip");
        this.localClipFailureReason = Objects.requireNonNull(reason, "reason");
    }

    public boolean hasLocalClip() {
        return localClipPath != null;
    }

    /**
     * Fija desde qué segundo del clip se muestra. Solo valida el número
     * (finito y >= 0): que el clip alcance (offset + duración <= duración
     * real del clip) lo comprueba quien conoce el archivo.
     */
    public void setClipStartOffset(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0) {
            throw new IllegalArgumentException("El clipStartOffset de la escena '" + id
                    + "' debe ser un número de segundos >= 0, llegó: " + seconds + ".");
        }
        this.clipStartOffset = seconds;
    }

    public boolean isReadyToGenerate() {
        return isCostApproved() && generationStatus == GenerationStatus.NOT_STARTED;
    }

    /**
     * Resetea una escena ya estimada/generada para volver a pasar por
     * estimate→aprobar costo→generar, típicamente porque cambió el modelo o
     * la tarifa configurada (ver KnownHiggsfieldPricing) y el resultado
     * anterior ya no sirve (video con orientación incorrecta, tarifa vieja, etc).
     * A propósito NO toca narrationAudioPath, targetDuration, promptStatus,
     * visualPrompt ni chosenTier: la narración real y las decisiones de la
     * puerta 1 siguen siendo válidas, solo se descarta lo técnico de
     * costo/generación. Requiere prompt aprobado. Se rechaza si hay una
     * generación en curso en Higgsfield (QUEUED o IN_PROGRESS — ambas son
     * "enviada a Higgsfield y todavía no terminal"), porque borrar el
     * requestId dejaría un job corriendo y cobrando sin que nadie lo vuelva
     * a consultar.
     */
    public void resetForRegeneration() {
        requirePromptApproved("resetear la generación");
        if (generationStatus == GenerationStatus.QUEUED || generationStatus == GenerationStatus.IN_PROGRESS) {
            throw new IllegalStateException(
                    "No se puede resetear la escena " + id + " con generación en curso (estado=" + generationStatus
                            + ", requestId=" + higgsfieldRequestId + "): espera a que termine con poll-generation.");
        }
        discardCost();
        this.generationStatus = GenerationStatus.NOT_STARTED;
        this.higgsfieldRequestId = null;
        this.higgsfieldStatusUrl = null;
        this.generatedAssetUrl = null;
        this.generationFailureReason = null;
        // El archivo en disco lo borra quien llama (ClipStore): Scene no hace E/S.
        this.localClipPath = null;
        this.localClipFailureReason = null;
        // El corte era del clip descartado; generateApprovedScenes fija el del clip nuevo.
        this.clipStartOffset = 0;
    }

    /** Vuelve el costo a NOT_ESTIMATED: hay que estimarlo y aprobarlo de nuevo en la PUERTA 2. */
    private void discardCost() {
        this.costStatus = SceneCostStatus.NOT_ESTIMATED;
        this.costEstimate = null;
        this.costRejectionNote = null;
        this.chosenModelId = null;
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

    private void requireCompleted(String action) {
        if (generationStatus != GenerationStatus.COMPLETED) {
            throw new IllegalStateException(
                    "No se puede " + action + " para la escena '" + id
                            + "': la generación no está completa (estado: " + generationStatus + ").");
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
                generatedAssetUrl, generationFailureReason,
                localClipPath, localClipFailureReason,
                clipStartOffset);
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
        scene.localClipPath = snapshot.localClipPath();
        scene.localClipFailureReason = snapshot.localClipFailureReason();
        scene.clipStartOffset = snapshot.clipStartOffset();
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
    public String localClipPath() { return localClipPath; }
    public String localClipFailureReason() { return localClipFailureReason; }
    public double clipStartOffset() { return clipStartOffset; }

    @Override
    public String toString() {
        return "Scene[" + order + " " + role + " id=" + id + " prompt=" + promptStatus
                + " cost=" + costStatus + " gen=" + generationStatus + "]";
    }
}
