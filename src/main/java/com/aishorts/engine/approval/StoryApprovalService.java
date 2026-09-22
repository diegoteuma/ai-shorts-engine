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
import com.aishorts.engine.higgsfield.KnownHiggsfieldPricing;
import com.aishorts.engine.tts.TtsResult;
import com.aishorts.engine.tts.TtsService;

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

    private final HiggsfieldClient higgsfieldClient;
    private final HiggsfieldConfig higgsfieldConfig;
    private final SceneDifficultyScorer difficultyScorer;
    private final NarrationDurationEstimator durationEstimator;
    private final TtsService ttsService;

    public StoryApprovalService(
            HiggsfieldClient higgsfieldClient,
            HiggsfieldConfig higgsfieldConfig,
            SceneDifficultyScorer difficultyScorer,
            NarrationDurationEstimator durationEstimator,
            TtsService ttsService
    ) {
        this.higgsfieldClient = higgsfieldClient;
        this.higgsfieldConfig = higgsfieldConfig;
        this.difficultyScorer = difficultyScorer;
        this.durationEstimator = durationEstimator;
        this.ttsService = ttsService;
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
                    if (decision.revisedNarrationText() != null && !decision.revisedNarrationText().isBlank()) {
                        scene.updateNarration(
                                decision.revisedNarrationText(),
                                durationEstimator.estimate(decision.revisedNarrationText()));
                    }
                    scene.approvePrompt(decision.revisedVisualPrompt(), decision.tierOverride());
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
                TtsResult result = ttsService.synthesize(scene.narrationText());
                Files.createDirectories(audioOutputDir);
                Path audioPath = audioOutputDir.resolve(scene.id() + ".mp3");
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

    private Map<String, Object> buildGenerationParameters(Scene scene, String modelId) {
        // La duración va como parámetro estructurado (igual que aspect_ratio
        // más abajo), nunca como texto dentro del prompt: así Higgsfield la
        // recibe de forma inequívoca, y estimate/generate usan exactamente
        // los mismos parámetros, como exige la API para que el estimado sea
        // confiable.
        //
        // TODO: aspect_ratio y resolution quedan hardcodeados a los valores
        // del piloto (vertical 9:16) hasta que se decida si van a variar por
        // escena o por historia.
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("prompt", scene.visualPrompt());
        parameters.put("duration", secondsRoundedUp(scene.targetDuration()));
        parameters.put("aspect_ratio", "9:16");

        // Kling 3.0 Standard (PREMIUM) genera audio propio salvo que se le
        // pida explícitamente que no — sin este parámetro se paga por un
        // audio que igual se descarta (VideoMontageBuilder pone la
        // narración real encima, ver montage/). Kling 2.5 Turbo Pro
        // (STANDARD) no tiene un parámetro equivalente — su schema solo
        // acepta prompt/duration/cfg_scale/negative_prompt — así que no lo
        // necesita. Si se agrega otro modelo con audio propio, sumarlo acá
        // con el mismo tipo de chequeo por modelId para no repetir el
        // desperdicio.
        if (KnownHiggsfieldPricing.PREMIUM_MODEL_ID.equals(modelId)) {
            parameters.put("sound", "off");
        }
        return parameters;
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
