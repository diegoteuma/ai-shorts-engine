package com.aishorts.engine.approval;

import com.aishorts.engine.difficulty.DifficultyFactors;
import com.aishorts.engine.difficulty.SceneDifficultyScorer;
import com.aishorts.engine.difficulty.TierRecommendation;
import com.aishorts.engine.domain.Scene;
import com.aishorts.engine.domain.Story;
import com.aishorts.engine.duration.NarrationDurationEstimator;
import com.aishorts.engine.higgsfield.EstimateRequest;
import com.aishorts.engine.higgsfield.EstimateResponse;
import com.aishorts.engine.higgsfield.GenerationRequest;
import com.aishorts.engine.higgsfield.GenerationResponse;
import com.aishorts.engine.higgsfield.GenerationStatusResponse;
import com.aishorts.engine.higgsfield.HiggsfieldClient;
import com.aishorts.engine.higgsfield.HiggsfieldConfig;
import com.aishorts.engine.tts.TtsResult;
import com.aishorts.engine.tts.TtsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orquesta el ciclo de vida completo de una historia respetando las dos
 * puertas humanas: NUNCA se llama a estimateCost sin que el prompt de la
 * escena esté aprobado, y NUNCA se llama a submitGeneration sin que el costo
 * de la escena esté aprobado. Esas dos reglas están garantizadas dos veces:
 * acá (el servicio filtra por lo que corresponde en cada paso) y en
 * {@link Scene} (que lanza IllegalStateException si se lo llama fuera de
 * orden). No hay ningún método en esta clase, ni en ningún llamador posible,
 * que encadene automáticamente propuesta -> generación sin pasar por las
 * dos aprobaciones explícitas.
 */
public final class StoryApprovalService {

    private static final Logger log = LoggerFactory.getLogger(StoryApprovalService.class);

    private final HiggsfieldClient higgsfieldClient;
    private final HiggsfieldConfig higgsfieldConfig;
    private final SceneDifficultyScorer difficultyScorer;
    private final NarrationDurationEstimator durationEstimator;
    private final TtsService ttsService;
    /**
     * Segundos de más que se piden a Higgsfield SOLO para el GANCHO
     * (engine.hook.lead-seconds): el modelo de texto a video arranca
     * tranquilo y llega al pico a los ~2 s, así que se genera un clip más
     * largo y se muestra desde este segundo (Scene.clipStartOffset).
     */
    private final double hookLeadSeconds;

    /** Sin margen de arranque para el GANCHO (hookLeadSeconds = 0), el comportamiento anterior. */
    public StoryApprovalService(
            HiggsfieldClient higgsfieldClient,
            HiggsfieldConfig higgsfieldConfig,
            SceneDifficultyScorer difficultyScorer,
            NarrationDurationEstimator durationEstimator,
            TtsService ttsService
    ) {
        this(higgsfieldClient, higgsfieldConfig, difficultyScorer, durationEstimator, ttsService, 0);
    }

    public StoryApprovalService(
            HiggsfieldClient higgsfieldClient,
            HiggsfieldConfig higgsfieldConfig,
            SceneDifficultyScorer difficultyScorer,
            NarrationDurationEstimator durationEstimator,
            TtsService ttsService,
            double hookLeadSeconds
    ) {
        if (!Double.isFinite(hookLeadSeconds) || hookLeadSeconds < 0) {
            throw new IllegalArgumentException("engine.hook.lead-seconds debe ser >= 0, llegó: " + hookLeadSeconds);
        }
        this.higgsfieldClient = higgsfieldClient;
        this.higgsfieldConfig = higgsfieldConfig;
        this.difficultyScorer = difficultyScorer;
        this.durationEstimator = durationEstimator;
        this.ttsService = ttsService;
        this.hookLeadSeconds = hookLeadSeconds;
    }

    /**
     * Paso 0: calcula y adjunta la sugerencia de tier a cada escena de la
     * historia que todavía no tiene una, para que se muestren junto al
     * prompt en la pantalla de revisión por lote. No aprueba nada por sí
     * solo.
     */
    public void proposeTiersForReview(Story story, Map<String, DifficultyFactors> factorsBySceneId) {
        for (Scene scene : story.scenes()) {
            if (scene.tierRecommendation() != null) {
                continue;
            }
            DifficultyFactors factors = factorsBySceneId.get(scene.id());
            if (factors == null) {
                throw new IllegalArgumentException(
                        "Falta DifficultyFactors para la escena '" + scene.id() + "' (" + scene.role() + ").");
            }
            TierRecommendation recommendation = difficultyScorer.score(scene.role(), factors);
            scene.proposeTier(recommendation);
        }
    }

    /**
     * Puerta 1, por lote: aplica todas tus decisiones sobre los prompts de
     * la historia de una sola vez. Una escena rechazada no bloquea a las
     * demás.
     */
    public BatchResult applyPromptDecisions(Story story, List<PromptDecision> decisions) {
        List<String> succeeded = new java.util.ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();

        for (PromptDecision decision : decisions) {
            Scene scene = findScene(story, decision.sceneId());
            try {
                if (decision.decision() == Decision.APPROVE) {
                    // Primero el prompt: si la revisión se rechaza, la narración queda intacta.
                    scene.approvePrompt(decision.revisedVisualPrompt(), decision.tierOverride());
                    if (decision.revisedNarrationText() != null && !decision.revisedNarrationText().isBlank()) {
                        scene.updateNarration(
                                decision.revisedNarrationText(),
                                durationEstimator.estimate(decision.revisedNarrationText()));
                    }
                    succeeded.add(scene.id());
                } else {
                    scene.rejectPrompt(decision.note());
                    succeeded.add(scene.id());
                }
            } catch (RuntimeException e) {
                failed.put(scene.id(), e.getMessage());
            }
        }
        return new BatchResult(succeeded, failed);
    }

    /**
     * Entre puerta 1 y puerta 2: sintetiza el audio de narración para cada
     * escena con prompt aprobado que todavía no lo tiene, y reemplaza la
     * duración estimada por conteo de palabras por la duración real del
     * audio. Se llama después de la puerta 1 a propósito — es contenido
     * pago, aunque sea de centavos, y no tiene sentido sintetizar texto que
     * todavía podrías rechazar.
     */
    public BatchResult synthesizeNarrationForApprovedScenes(Story story, Path audioOutputDir) {
        List<String> succeeded = new java.util.ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();

        for (Scene scene : story.scenes()) {
            if (!scene.isPromptApproved() || scene.hasNarrationAudio()) {
                continue;
            }
            try {
                Path audioPath = audioOutputDir.resolve(scene.id() + ".mp3");
                // Defensa en profundidad (el id de historia ya se valida en la entrada y en el repositorio):
                // el mp3 tiene que caer directamente en audioOutputDir. Se comprueba ANTES de pagar la síntesis.
                Path dir = audioOutputDir.toAbsolutePath().normalize();
                if (!dir.equals(audioPath.toAbsolutePath().normalize().getParent())) {
                    throw new IllegalArgumentException("La ruta del audio de la escena '" + scene.id()
                            + "' queda fuera de " + dir + "; no se escribe.");
                }
                TtsResult result = ttsService.synthesize(scene.narrationText());
                Files.createDirectories(audioOutputDir);
                Files.write(audioPath, result.audioBytes());
                scene.attachNarrationAudio(audioPath.toString(), result.duration());
                succeeded.add(scene.id());
            } catch (RuntimeException | IOException e) {
                failed.put(scene.id(), e.getMessage());
            }
        }
        return new BatchResult(succeeded, failed);
    }

    /**
     * Puerta 1 -> Puerta 2: consulta el costo real en Higgsfield para cada
     * escena que ya tiene el prompt aprobado y todavía no tiene estimación.
     * No aprueba ningún costo, solo lo consulta y lo deja listo para tu
     * revisión.
     */
    public BatchResult estimateCostsForApprovedScenes(Story story) {
        List<String> succeeded = new java.util.ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();

        for (Scene scene : story.scenesNeedingCostEstimate()) {
            try {
                String modelId = higgsfieldConfig.modelIdFor(scene.chosenTier());
                Map<String, Object> parameters = buildGenerationParameters(scene, modelId);
                EstimateResponse estimate = higgsfieldClient.estimateCost(new EstimateRequest(modelId, parameters));
                scene.recordCostEstimate(estimate, modelId);
                succeeded.add(scene.id());
            } catch (RuntimeException e) {
                failed.put(scene.id(), e.getMessage());
            }
        }
        return new BatchResult(succeeded, failed);
    }

    /**
     * Puerta 2, por lote: aplica todas tus decisiones sobre los costos de la
     * historia de una sola vez. Un rechazo de costo no bloquea a las demás
     * escenas.
     */
    public BatchResult applyCostDecisions(Story story, List<CostDecision> decisions) {
        List<String> succeeded = new java.util.ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();

        for (CostDecision decision : decisions) {
            Scene scene = findScene(story, decision.sceneId());
            try {
                if (decision.decision() == Decision.APPROVE) {
                    scene.approveCost();
                } else {
                    scene.rejectCost(decision.note());
                }
                succeeded.add(scene.id());
            } catch (RuntimeException e) {
                failed.put(scene.id(), e.getMessage());
            }
        }
        return new BatchResult(succeeded, failed);
    }

    /**
     * Único punto del sistema que dispara generación real y gasto en
     * Higgsfield. Solo toma las escenas con costo APPROVED
     * ({@link Story#scenesReadyToGenerate()}) — nunca se invoca automáticamente
     * después de applyCostDecisions ni desde ningún otro lugar; hay que
     * llamarlo a propósito, como un paso explícito más.
     */
    public BatchResult generateApprovedScenes(Story story) {
        List<String> succeeded = new java.util.ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();

        for (Scene scene : story.scenesReadyToGenerate()) {
            try {
                Map<String, Object> parameters = buildGenerationParameters(scene, scene.chosenModelId());
                GenerationResponse response = higgsfieldClient.submitGeneration(
                        new GenerationRequest(scene.chosenModelId(), parameters));
                scene.startGeneration(response.requestId(), response.statusUrl());
                // El corte por defecto es el margen que se pidió de más (0 fuera del GANCHO).
                scene.setClipStartOffset(leadSecondsFor(scene));
                succeeded.add(scene.id());
            } catch (RuntimeException e) {
                failed.put(scene.id(), e.getMessage());
            }
        }
        return new BatchResult(succeeded, failed);
    }

    /**
     * La generación en Higgsfield es asincrónica: {@link #generateApprovedScenes}
     * solo la dispara y deja la escena en QUEUED. Este método es el que falta
     * para saber cuándo terminó — hay que llamarlo periódicamente (polling)
     * para cada escena QUEUED o IN_PROGRESS hasta que la historia esté
     * generada ({@link Story#allGenerationFinished()}). No es un webhook, es
     * responsabilidad de quien orquesta el flujo (la demo, la API REST)
     * llamarlo con la frecuencia que le convenga.
     */
    public BatchResult pollGenerationStatus(Story story) {
        List<String> succeeded = new java.util.ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();

        for (Scene scene : story.scenes()) {
            if (scene.generationStatus() != com.aishorts.engine.domain.GenerationStatus.QUEUED
                    && scene.generationStatus() != com.aishorts.engine.domain.GenerationStatus.IN_PROGRESS) {
                continue;
            }
            try {
                GenerationStatusResponse status = higgsfieldClient.pollStatus(scene.higgsfieldStatusUrl());
                applyStatus(scene, status);
                succeeded.add(scene.id());
            } catch (RuntimeException e) {
                failed.put(scene.id(), e.getMessage());
            }
        }
        return new BatchResult(succeeded, failed);
    }

    /**
     * Interpreta los 6 valores reales de status que documenta el spec de
     * Higgsfield (docs.higgsfield.ai/docs/openapi.json, schema RequestStatus):
     * queued, in_progress, nsfw, failed, completed, canceled. nsfw y
     * canceled son estados TERMINALES de fallo — si se los tratara como "en
     * curso" (un catch-all genérico), esta escena quedaría QUEUED/IN_PROGRESS
     * para siempre y {@link #pollGenerationStatus} la polearía indefinidamente,
     * porque Higgsfield nunca va a devolver otra cosa para un request ya
     * cancelado o marcado NSFW.
     */
    private void applyStatus(Scene scene, GenerationStatusResponse status) {
        String value = status.status();
        if (value == null) {
            throw new IllegalStateException("Higgsfield no devolvió 'status' para la escena '" + scene.id() + "'.");
        }
        switch (value) {
            case "completed" -> {
                if (status.outputUrl() == null) {
                    throw new IllegalStateException(
                            "Higgsfield marcó la escena '" + scene.id() + "' como completa pero no devolvió una URL de salida.");
                }
                scene.completeGeneration(status.outputUrl());
            }
            case "failed" -> scene.failGeneration(
                    status.failureReason() != null ? status.failureReason() : "Higgsfield reportó un fallo sin detalle.");
            case "nsfw" -> scene.failGeneration("Higgsfield marcó el contenido como NSFW.");
            case "canceled" -> scene.failGeneration("La generación fue cancelada.");
            case "queued", "in_progress" -> scene.markInProgress();
            default -> throw new IllegalStateException(
                    "Higgsfield devolvió un status desconocido para la escena '" + scene.id() + "': '" + value + "'.");
        }
    }

    /**
     * Resetea todas las escenas de la historia para volver a pasar por
     * estimate→puerta 2→generate (ver {@link Scene#resetForRegeneration()}).
     * No dispara nada automáticamente.
     */
    public BatchResult resetAllScenesForRegeneration(Story story) {
        return resetScenesForRegeneration(story, story.scenes().stream().map(Scene::id).toList());
    }

    /**
     * Resetea las escenas indicadas para volver a pasar por
     * estimate→puerta 2→generate. No dispara nada automáticamente. Lista
     * vacía o null es un error (para resetear todo se usa
     * {@link #resetAllScenesForRegeneration}, así una lista vacía mandada
     * por error no borra todo en silencio).
     */
    public BatchResult resetScenesForRegeneration(Story story, List<String> sceneIds) {
        if (sceneIds == null || sceneIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "sceneIds no puede estar vacío; para resetear todas las escenas omite el body");
        }
        List<String> succeeded = new java.util.ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();

        for (String sceneId : sceneIds.stream().distinct().toList()) {
            try {
                Scene scene = findScene(story, sceneId);
                log.info(
                        "Reseteando escena '{}' para regeneración (último intento: chosenModelId={}, "
                                + "costEstimate={}, generatedAssetUrl={})",
                        sceneId, scene.chosenModelId(), scene.costEstimate(), scene.generatedAssetUrl());
                scene.resetForRegeneration();
                succeeded.add(sceneId);
            } catch (RuntimeException e) {
                failed.put(sceneId, e.getMessage());
            }
        }
        return new BatchResult(succeeded, failed);
    }

    private Map<String, Object> buildGenerationParameters(Scene scene, String modelId) {
        // La duración va como parámetro estructurado (igual que aspect_ratio
        // más abajo), nunca como texto dentro del prompt: así Higgsfield la
        // recibe de forma inequívoca, y estimate/generate usan exactamente
        // los mismos parámetros, como exige la API para que el estimado sea
        // confiable.
        //
        // TODO: aspect_ratio y resolution quedan hardcodeados a los valores
        // del piloto (vertical 9:16 a 720p) hasta que se decida si van a
        // variar por escena o por historia.
        //
        // IMPORTANTE: mandar aspect_ratio y resolution SIEMPRE explícitos,
        // nunca confiar en el default de Higgsfield — varía por modelo y
        // puede salir caro en silencio. Wan 3.0 Prime, por ejemplo, defaultea
        // a resolution "1080p" (el doble de precio que "720p") y aspect_ratio
        // "adaptive" (no necesariamente 9:16 vertical).
        //
        // roundUpToAllowedDuration es obligatorio acá: cada modelo tiene su
        // propio rango/enum de duración permitida (ver DurationPolicy en
        // KnownHiggsfieldPricing) y rechaza con 400 cualquier otro valor, así
        // que la duración cruda de la escena nunca puede llegar tal cual a
        // Higgsfield.
        //
        // En el GANCHO se suma el margen de arranque (hookLeadSeconds) ANTES
        // de redondear: estimate y generate usan este mismo método, así que
        // el costo de la PUERTA 2 ya incluye los segundos de más.
        long leadMillis = Math.round(leadSecondsFor(scene) * 1000);
        long requestedSeconds = secondsRoundedUp(scene.targetDuration().plusMillis(leadMillis));
        long allowedSeconds = higgsfieldConfig.pricingFor(modelId).roundUpToAllowedDuration(requestedSeconds);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("prompt", scene.visualPrompt());
        parameters.put("duration", allowedSeconds);
        parameters.put("aspect_ratio", "9:16");
        parameters.put("resolution", "720p");

        // Seedance 2.0 genera audio propio por defecto (generate_audio=true)
        // — sin desactivarlo se paga por un audio que igual se descarta
        // (la narración real se pone encima en la edición manual).
        // STANDARD y PREMIUM comparten el mismo modelo de Higgsfield (ver
        // KnownHiggsfieldPricing), así que esto siempre aplica, sin
        // distinción por tier.
        parameters.put("generate_audio", false);

        return parameters;
    }

    /** El margen de arranque de la escena: hookLeadSeconds en el GANCHO, 0 en las demás. */
    private double leadSecondsFor(Scene scene) {
        return scene.role() == com.aishorts.engine.domain.SceneRole.GANCHO ? hookLeadSeconds : 0;
    }

    private static long secondsRoundedUp(java.time.Duration duration) {
        return Math.max(1, (duration.toMillis() + 999) / 1000);
    }

    private Scene findScene(Story story, String sceneId) {
        return story.scenes().stream()
                .filter(s -> s.id().equals(sceneId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "La historia '" + story.id() + "' no tiene una escena con id '" + sceneId + "'."));
    }
}
