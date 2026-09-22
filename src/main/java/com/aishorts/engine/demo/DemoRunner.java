package com.aishorts.engine.demo;

import com.aishorts.engine.approval.BatchResult;
import com.aishorts.engine.approval.CostDecision;
import com.aishorts.engine.approval.PromptDecision;
import com.aishorts.engine.approval.StoryApprovalService;
import com.aishorts.engine.difficulty.DefaultSceneDifficultyScorer;
import com.aishorts.engine.difficulty.DifficultyFactors;
import com.aishorts.engine.domain.GenerationTier;
import com.aishorts.engine.domain.Scene;
import com.aishorts.engine.domain.SceneRole;
import com.aishorts.engine.domain.Story;
import com.aishorts.engine.duration.DurationBudget;
import com.aishorts.engine.duration.NarrationDurationEstimator;
import com.aishorts.engine.duration.WordsPerSecondDurationEstimator;
import com.aishorts.engine.higgsfield.GenerationStatusResponse;
import com.aishorts.engine.higgsfield.HiggsfieldClient;
import com.aishorts.engine.higgsfield.HiggsfieldConfig;
import com.aishorts.engine.script.ScriptDraftingService;
import com.aishorts.engine.script.StoryBrief;
import com.aishorts.engine.script.StoryDraftingService;
import com.aishorts.engine.subtitles.BilingualCaptions;
import com.aishorts.engine.subtitles.StorySubtitleBuilder;
import com.aishorts.engine.tts.TtsService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Simula el prototipo Tunguska de punta a punta, empezando desde el
 * StoryBrief (lo que vos ya aprobaste) hasta la generación, sin llamar a
 * ninguna red real:
 *
 *   0) StoryBrief -> StoryDraftingService (LLM simulado) -> Story con
 *      duración estimada por escena YA CALCULADA antes de cualquier aprobación
 *   1) proponer tier sugerido para las 6 escenas
 *   2) aprobar el LOTE completo de prompts (con una revisión de texto visual,
 *      un rechazo que no bloquea al resto, y un reintento con revisión de
 *      NARRACIÓN para mostrar que el presupuesto de tiempo se recalcula)
 *   3) recién ahí sintetizar voz real (ElevenLabs simulado) — la duración
 *      pasa de estimada por palabras a la del audio real
 *   4) recién ahí consultar costos reales en Higgsfield (duration va como
 *      parámetro estructurado, con el valor real, no como texto del prompt)
 *   5) aprobar el LOTE completo de costos
 *   6) recién ahí generar (nunca antes, nunca automático)
 *
 * Correr con: java -cp out com.aishorts.engine.demo.DemoRunner
 */
public final class DemoRunner {

    public static void main(String[] args) throws IOException {
        DurationBudget durationBudget = DurationBudget.ofSeconds(35, 40);
        NarrationDurationEstimator durationEstimator = WordsPerSecondDurationEstimator.neutralSpanish();

        StoryBrief brief = new StoryBrief(
                "Explosión de Tunguska (1908)",
                "La explosión que derribó 80 millones de árboles",
                List.of(
                        "El 30 de junio de 1908 un objeto espacial explotó en la atmósfera sobre Siberia.",
                        "La onda expansiva derribó aproximadamente 80 millones de árboles en más de 2.000 km² de bosque.",
                        "La destrucción se produjo por una explosión aérea, sin un cráter de impacto convencional."
                ),
                List.of(
                        "Primero mostrar algo impactante y comprensible; recién en CONTEXTO revelar el nombre Tunguska.",
                        "El GIRO debe indicar con claridad que a partir de ahí empieza la especulación.",
                        "La ciudad del IF es una hipótesis, no parte del hecho histórico ni una predicción.",
                        "No inventar cifras de víctimas ni de daños en la parte IF."
                ),
                durationBudget
        );

        // Paso 0: borrador de guion (simulado acá; en producción llama a Claude).
        ScriptDraftingService scriptDraftingService = new FakeScriptDraftingService();
        StoryDraftingService storyDraftingService = new StoryDraftingService(scriptDraftingService, durationEstimator);
        Story story = storyDraftingService.draftStory("tunguska-prototipo", brief);

        System.out.println("=== " + story.title() + " ===");
        System.out.println("Estado inicial: " + story.status());
        System.out.println();

        System.out.println("--- Borrador recién armado (antes de cualquier aprobación) ---");
        for (Scene scene : story.scenes()) {
            System.out.printf("[%d] %-13s %2ds  \"%s\"%n",
                    scene.order(), scene.role(), scene.targetDuration().toSeconds(), scene.narrationText());
        }
        printDurationCheck(story, durationBudget);
        System.out.println();

        HiggsfieldConfig config = new HiggsfieldConfig(
                "https://platform.higgsfield.ai",
                "fake-key-id",
                "fake-key-secret",
                Map.of(
                        GenerationTier.STANDARD, "higgsfield-ai/soul/standard",
                        GenerationTier.PREMIUM, "higgsfield-ai/soul-premium/cinema"
                ),
                Map.of() // FakeHiggsfieldClient no consulta tarifas, calcula su propio costo fijo.
        );
        HiggsfieldClient client = new FakeHiggsfieldClient();
        TtsService ttsService = new FakeTtsService(durationEstimator);
        StoryApprovalService service = new StoryApprovalService(
                client, config, new DefaultSceneDifficultyScorer(), durationEstimator, ttsService);

        // Paso 1: proponer tier por escena, a partir de la dificultad visual.
        service.proposeTiersForReview(story, difficultyFactorsBySceneId(story));
        System.out.println("--- Prompts propuestos (puerta 1, revisión por lote) ---");
        for (Scene scene : story.scenes()) {
            System.out.printf(
                    "[%d] %-13s tier sugerido=%-8s score=%-3d %2ds  prompt=\"%s\"%n",
                    scene.order(), scene.role(), scene.tierRecommendation().tier(),
                    scene.tierRecommendation().score(), scene.targetDuration().toSeconds(), scene.visualPrompt());
        }
        System.out.println("Estado: " + story.status());
        System.out.println();

        // Puerta 1: aprobás el lote completo. CONTEXTO llega con una revisión
        // del prompt visual; CIERRE se rechaza para mostrar que no bloquea al resto.
        List<PromptDecision> promptDecisions = new ArrayList<>();
        for (Scene scene : story.scenes()) {
            if (scene.role() == SceneRole.CONTEXTO) {
                promptDecisions.add(PromptDecision.approveWithVisualRevision(
                        scene.id(), scene.visualPrompt() + " — mapa con fecha en pantalla, estilo documental"));
            } else if (scene.role() == SceneRole.CIERRE) {
                promptDecisions.add(PromptDecision.reject(scene.id(), "el fundido a negro se ve muy abrupto"));
            } else {
                promptDecisions.add(PromptDecision.approve(scene.id()));
            }
        }
        BatchResult promptResult = service.applyPromptDecisions(story, promptDecisions);
        System.out.println("--- Resultado aprobación de prompts (lote) ---");
        System.out.println("Aprobadas/rechazadas aplicadas: " + promptResult.succeededSceneIds().size());
        System.out.println("Fallas al aplicar: " + promptResult.failedSceneIds());
        System.out.println("Estado: " + story.status() + " (CIERRE queda rechazada, no bloquea al resto)");
        System.out.println();

        // Reintento de CIERRE: esta vez con una revisión de NARRACIÓN (no solo
        // visual), para mostrar que el presupuesto de tiempo se recalcula.
        Scene cierre = story.scenes().stream().filter(s -> s.role() == SceneRole.CIERRE).findFirst().orElseThrow();
        System.out.println("Reintento de CIERRE con narración más larga (antes: "
                + cierre.targetDuration().toSeconds() + "s) ...");
        BatchResult retryResult = service.applyPromptDecisions(story, List.of(
                PromptDecision.approveWithNarrationRevision(cierre.id(),
                        "¿Qué pasaría si una explosión así ocurriera hoy, sobre una ciudad y no sobre un bosque vacío?")
        ));
        System.out.println("Reintento aplicado: " + retryResult.succeededSceneIds()
                + ", fallas: " + retryResult.failedSceneIds());
        System.out.println("CIERRE ahora dura " + cierre.targetDuration().toSeconds() + "s");
        System.out.println("Estado: " + story.status());
        printDurationCheck(story, durationBudget);
        System.out.println();

        // Entre puerta 1 y puerta 2: síntesis de voz real. La duración deja
        // de ser una estimación por conteo de palabras acá.
        System.out.println("--- Síntesis de narración (duración pasa de estimada a real) ---");
        for (Scene scene : story.scenes()) {
            if (scene.isPromptApproved()) {
                System.out.printf("[%d] %-13s antes (estimada): %ds%n",
                        scene.order(), scene.role(), scene.targetDuration().toSeconds());
            }
        }
        BatchResult ttsResult = service.synthesizeNarrationForApprovedScenes(story, Path.of("audio"));
        System.out.println("Escenas sintetizadas: " + ttsResult.succeededSceneIds().size()
                + ", fallas: " + ttsResult.failedSceneIds());
        for (Scene scene : story.scenes()) {
            if (scene.hasNarrationAudio()) {
                System.out.printf("[%d] %-13s después (audio real): %ds  -> %s%n",
                        scene.order(), scene.role(), scene.targetDuration().toSeconds(), scene.narrationAudioPath());
            }
        }
        printDurationCheck(story, durationBudget);
        System.out.println();

        // Puerta 1 -> Puerta 2: consultar costo real solo de lo aprobado.
        BatchResult estimateResult = service.estimateCostsForApprovedScenes(story);
        System.out.println("--- Costos estimados (consulta a Higgsfield, duration va como parámetro) ---");
        for (Scene scene : story.scenes()) {
            if (scene.costEstimate() != null) {
                System.out.printf("[%d] %-13s tier=%-8s %2ds  costo=%s %s%n",
                        scene.order(), scene.role(), scene.chosenTier(), scene.targetDuration().toSeconds(),
                        scene.costEstimate().cost(), scene.costEstimate().currency());
            }
        }
        System.out.println("Total estimado: " + story.totalEstimatedCost() + " USD");
        System.out.println("Fallas al estimar: " + estimateResult.failedSceneIds());
        System.out.println("Estado: " + story.status());
        System.out.println();

        // Puerta 2: aprobás el lote completo de costos.
        List<CostDecision> costDecisions = story.scenes().stream()
                .filter(s -> s.costEstimate() != null)
                .map(s -> CostDecision.approve(s.id()))
                .toList();
        BatchResult costResult = service.applyCostDecisions(story, costDecisions);
        System.out.println("--- Resultado aprobación de costos (lote) ---");
        System.out.println("Aplicadas: " + costResult.succeededSceneIds().size() + ", fallas: " + costResult.failedSceneIds());
        System.out.println("Estado: " + story.status());
        System.out.println();

        // Recién acá se genera. Este paso nunca se dispara solo.
        BatchResult generationResult = service.generateApprovedScenes(story);
        System.out.println("--- Generación disparada explícitamente ---");
        System.out.println("Escenas enviadas a generar: " + generationResult.succeededSceneIds().size());
        System.out.println("Estado: " + story.status());
        for (Scene scene : story.scenes()) {
            if (scene.higgsfieldStatusUrl() != null) {
                GenerationStatusResponse status = client.pollStatus(scene.higgsfieldStatusUrl());
                if ("completed".equals(status.status())) {
                    scene.completeGeneration(status.outputUrl());
                } else if ("failed".equals(status.status())) {
                    scene.failGeneration(status.failureReason());
                }
            }
        }
        System.out.println("Estado final: " + story.status());
        System.out.println();

        writeBilingualSrt(story);
    }

    private static void printDurationCheck(Story story, DurationBudget budget) {
        Duration total = story.totalTargetDuration();
        boolean withinBudget = story.isWithinDurationBudget(budget);
        System.out.printf("Duración total estimada: %ds (objetivo %d-%ds) -> %s%n",
                total.toSeconds(), budget.min().toSeconds(), budget.max().toSeconds(),
                withinBudget ? "DENTRO del presupuesto" : "FUERA del presupuesto, revisar antes de aprobar");
    }

    private static Map<String, DifficultyFactors> difficultyFactorsBySceneId(Story story) {
        return Map.of(
                "tunguska-prototipo-gancho", new DifficultyFactors(6, 3, 7, 5),
                "tunguska-prototipo-explicacion", new DifficultyFactors(8, 5, 8, 6),
                "tunguska-prototipo-contexto", new DifficultyFactors(2, 3, 2, 2),
                "tunguska-prototipo-giro", new DifficultyFactors(3, 2, 3, 3),
                "tunguska-prototipo-consecuencia", new DifficultyFactors(9, 8, 9, 7),
                "tunguska-prototipo-cierre", new DifficultyFactors(3, 2, 4, 2)
        );
    }

    private static void writeBilingualSrt(Story story) throws IOException {
        // En producción, traducir recién acá (narraciones ya finales tras la
        // puerta 1) evita retraducir cada vez que se revisa un texto.
        StorySubtitleBuilder subtitleBuilder = new StorySubtitleBuilder(new FakeCaptionTranslationService());
        BilingualCaptions captions = subtitleBuilder.build(story);

        Path esPath = Path.of("tunguska-demo.es.srt");
        Path enPath = Path.of("tunguska-demo.en.srt");
        Files.writeString(esPath, captions.srtEs());
        Files.writeString(enPath, captions.srtEn());

        System.out.println("--- Subtítulos ---");
        System.out.println(".srt español (fuente para quemar en el montaje, coincide con el audio): " + esPath.toAbsolutePath());
        System.out.println(".srt inglés (pista de closed captions subida aparte, no se quema): " + enPath.toAbsolutePath());
        System.out.println("(el paso de quemado en sí -ffmpeg u otra herramienta sobre el video ya montado- todavía no está construido)");
    }
}
