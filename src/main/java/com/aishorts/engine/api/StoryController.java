package com.aishorts.engine.api;

import com.aishorts.engine.approval.BatchResult;
import com.aishorts.engine.approval.CostDecision;
import com.aishorts.engine.approval.PromptDecision;
import com.aishorts.engine.approval.StoryApprovalService;
import com.aishorts.engine.clips.ClipStore;
import com.aishorts.engine.clips.ClipSyncResult;
import com.aishorts.engine.difficulty.DifficultyFactors;
import com.aishorts.engine.domain.Story;
import com.aishorts.engine.domain.StoryIds;
import com.aishorts.engine.duration.ActualDurationChecker;
import com.aishorts.engine.drafts.StoryIndex;
import com.aishorts.engine.persistence.SnapshotMapper;
import com.aishorts.engine.persistence.StoryRepository;
import com.aishorts.engine.script.StoryBrief;
import com.aishorts.engine.script.StoryDraftingService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Expone {@link StoryApprovalService} (y la creación de historias, vía
 * {@link StoryDraftingService}) por HTTP, para el día en que el flujo de
 * las dos puertas humanas se opere desde una UI en vez de código Java
 * directo.
 *
 * Cada endpoint POST es EXACTAMENTE un paso del pipeline de
 * StoryApprovalService, nunca más de uno: la regla de "nunca encadenar
 * automáticamente propuesta -> generación" que ese servicio hace cumplir
 * en Java se traduce acá en que no existe, a propósito, ningún endpoint
 * que dispare más de un paso. Quien opera la UI decide cuándo se llama al
 * siguiente.
 *
 * El patrón de cada handler es siempre el mismo: leer la Story guardada
 * ({@link StoryRepository#findById}), llamar un único método de
 * StoryApprovalService, guardar el resultado, devolver el estado
 * actualizado. La persistencia es responsabilidad de este layer — ni
 * StoryApprovalService ni StoryDraftingService saben que un
 * StoryRepository existe.
 *
 * <pre>
 * POST   /stories                         crear (StoryBrief -> StoryDraftingService)
 * GET    /stories                         listar todas (resumen: id, title, topic, progress)
 * GET    /stories/{id}                    ver el estado completo de una
 * POST   /stories/{id}/tiers              proposeTiersForReview
 * POST   /stories/{id}/prompt-decisions   applyPromptDecisions   (PUERTA 1)
 * POST   /stories/{id}/narration          synthesizeNarrationForApprovedScenes
 *                                          + "durationWarnings": duración real fuera de las reglas (solo aviso)
 * POST   /stories/{id}/cost-estimates     estimateCostsForApprovedScenes (+ "durationWarnings")
 * POST   /stories/{id}/cost-decisions     applyCostDecisions     (PUERTA 2)
 * POST   /stories/{id}/generate           generateApprovedScenes
 * POST   /stories/{id}/poll-generation    pollGenerationStatus  (llamar seguido hasta que termine)
 *                                          Cuando TODAS las escenas quedan COMPLETED, además guarda los clips
 *                                          en engine.clips-dir (campo "clips" de la respuesta). Una descarga
 *                                          fallida no bloquea: se reintenta llamando de nuevo.
 * POST   /stories/{id}/reset-generation   resetAllScenesForRegeneration / resetScenesForRegeneration
 *                                          (body omitido -> todas; lista de sceneIds -> parcial; [] -> 400)
 *                                          Borra también la copia local del clip de las escenas reseteadas.
 * </pre>
 */
@RestController
@RequestMapping("/stories")
public class StoryController {

    private final StoryApprovalService approvalService;
    private final StoryDraftingService draftingService;
    private final StoryRepository repository;
    private final Path narrationAudioDir;
    private final StoryIndex storyIndex;
    private final ClipStore clipStore;
    private final ActualDurationChecker durationChecker;

    public StoryController(
            StoryApprovalService approvalService,
            StoryDraftingService draftingService,
            StoryRepository repository,
            @Qualifier("narrationAudioDir") Path narrationAudioDir,
            StoryIndex storyIndex,
            ClipStore clipStore,
            ActualDurationChecker durationChecker
    ) {
        this.approvalService = approvalService;
        this.draftingService = draftingService;
        this.repository = repository;
        this.narrationAudioDir = narrationAudioDir;
        this.storyIndex = storyIndex;
        this.clipStore = clipStore;
        this.durationChecker = durationChecker;
    }

    /**
     * Resumen {id, title, topic, progress} por historia. Cada archivo se lee
     * por separado (StoryIndex): uno corrupto aparece como {id, error} y no
     * rompe la lista. El estado completo de una sigue en GET /stories/{id}.
     */
    @GetMapping
    public List<Map<String, Object>> listStories() {
        return storyIndex.summaries();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createStory(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> requestBody = body != null ? body : Map.of();
        StoryBrief brief = RequestParsing.parseStoryBrief(requestBody);
        String storyId = requestBody.get("storyId") != null ? String.valueOf(requestBody.get("storyId")) : UUID.randomUUID().toString();
        StoryIds.requireValid(storyId); // 400: el id termina en nombres de archivo (historia, audios, clips)
        if (repository.findById(storyId).isPresent()) {
            throw new ApiError(409, "Ya existe una historia con id '" + storyId + "'.");
        }
        Story story = draftingService.draftStory(storyId, brief);
        repository.save(story);
        return ResponseEntity.status(HttpStatus.CREATED).body(SnapshotMapper.toMap(story.toSnapshot()));
    }

    @GetMapping("/{id}")
    public Map<String, Object> getStory(@PathVariable("id") String id) {
        return SnapshotMapper.toMap(loadOrNotFound(id).toSnapshot());
    }

    @PostMapping("/{id}/tiers")
    public Map<String, Object> proposeTiers(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body) {
        Story story = loadOrNotFound(id);
        Map<String, DifficultyFactors> factors = RequestParsing.parseDifficultyFactorsBySceneId(body != null ? body : Map.of());
        approvalService.proposeTiersForReview(story, factors);
        repository.save(story);
        return SnapshotMapper.toMap(story.toSnapshot());
    }

    @PostMapping("/{id}/prompt-decisions")
    public Map<String, Object> applyPromptDecisions(@PathVariable("id") String id, @RequestBody(required = false) List<Object> body) {
        Story story = loadOrNotFound(id);
        List<PromptDecision> decisions = RequestParsing.parsePromptDecisions(body != null ? body : List.of());
        BatchResult result = approvalService.applyPromptDecisions(story, decisions);
        repository.save(story);
        return batchResponse(result, story);
    }

    @PostMapping("/{id}/narration")
    public Map<String, Object> synthesizeNarration(@PathVariable("id") String id) {
        Story story = loadOrNotFound(id);
        BatchResult result = approvalService.synthesizeNarrationForApprovedScenes(story, narrationAudioDir);
        repository.save(story);
        Map<String, Object> response = batchResponse(result, story);
        response.put("durationWarnings", durationWarnings(story));
        return response;
    }

    @PostMapping("/{id}/cost-estimates")
    public Map<String, Object> estimateCosts(@PathVariable("id") String id) {
        Story story = loadOrNotFound(id);
        BatchResult result = approvalService.estimateCostsForApprovedScenes(story);
        repository.save(story);
        Map<String, Object> response = batchResponse(result, story);
        response.put("durationWarnings", durationWarnings(story));
        return response;
    }

    @PostMapping("/{id}/cost-decisions")
    public Map<String, Object> applyCostDecisions(@PathVariable("id") String id, @RequestBody(required = false) List<Object> body) {
        Story story = loadOrNotFound(id);
        List<CostDecision> decisions = RequestParsing.parseCostDecisions(body != null ? body : List.of());
        BatchResult result = approvalService.applyCostDecisions(story, decisions);
        repository.save(story);
        return batchResponse(result, story);
    }

    @PostMapping("/{id}/generate")
    public Map<String, Object> generate(@PathVariable("id") String id) {
        Story story = loadOrNotFound(id);
        BatchResult result = approvalService.generateApprovedScenes(story);
        repository.save(story);
        return batchResponse(result, story);
    }

    @PostMapping("/{id}/poll-generation")
    public Map<String, Object> pollGeneration(@PathVariable("id") String id) {
        Story story = loadOrNotFound(id);
        BatchResult result = approvalService.pollGenerationStatus(story);
        // Se persiste lo pollado ANTES de tocar red/disco: un fallo al descargar no puede perder el avance.
        repository.save(story);
        ClipSyncResult clips = clipStore.syncIfAllCompleted(story);
        if (clips.changedScenes()) {
            repository.save(story);
        }
        Map<String, Object> response = batchResponse(result, story);
        response.put("clips", clipsResponse(clips));
        return response;
    }

    @PostMapping("/{id}/reset-generation")
    public Map<String, Object> resetGeneration(
            @PathVariable("id") String id, @RequestBody(required = false) List<String> sceneIds) {
        Story story = loadOrNotFound(id);
        BatchResult result = (sceneIds == null)
                ? approvalService.resetAllScenesForRegeneration(story)
                : approvalService.resetScenesForRegeneration(story, sceneIds);
        repository.save(story);
        // Después de guardar: si el borrado falla queda un archivo huérfano (se pisa al regenerar), nunca un estado incoherente.
        clipStore.deleteClips(result.succeededSceneIds());
        return batchResponse(result, story);
    }

    // --- helpers ----------------------------------------------------------------------------

    private Story loadOrNotFound(String storyId) {
        return repository.findById(storyId)
                .orElseThrow(() -> new ApiError(404, "No existe una historia con id '" + storyId + "'."));
    }

    /**
     * Solo cuentan las escenas con audio: sin él, targetDuration sigue siendo
     * la estimación por palabras, no la duración real.
     */
    private List<ActualDurationChecker.Warning> durationWarnings(Story story) {
        List<ActualDurationChecker.SceneDuration> durations = story.scenes().stream()
                .map(s -> new ActualDurationChecker.SceneDuration(s.id(), s.hasNarrationAudio() ? s.targetDuration() : null))
                .toList();
        return durationChecker.check(durations);
    }

    private Map<String, Object> clipsResponse(ClipSyncResult clips) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("attempted", clips.attempted());
        map.put("storedSceneIds", clips.storedSceneIds());
        map.put("alreadyPresentSceneIds", clips.alreadyPresent());
        map.put("failedSceneIds", clips.failedSceneIds());
        return map;
    }

    private Map<String, Object> batchResponse(BatchResult result, Story story) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("succeededSceneIds", result.succeededSceneIds());
        map.put("failedSceneIds", result.failedSceneIds());
        map.put("story", SnapshotMapper.toMap(story.toSnapshot()));
        return map;
    }
}
