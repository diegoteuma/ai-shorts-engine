package com.aishorts.engine.api;

import com.aishorts.engine.approval.Decision;
import com.aishorts.engine.domain.Story;
import com.aishorts.engine.drafts.DraftDecision;
import com.aishorts.engine.drafts.DraftStatus;
import com.aishorts.engine.drafts.DraftText;
import com.aishorts.engine.drafts.ExistingStory;
import com.aishorts.engine.drafts.JsonFileDraftRepository;
import com.aishorts.engine.drafts.StoryDraft;
import com.aishorts.engine.drafts.StoryDraftService;
import com.aishorts.engine.drafts.StoryIndex;
import com.aishorts.engine.persistence.SnapshotMapper;
import com.aishorts.engine.persistence.StoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * El paso PREVIO al pipeline: propuestas de tema, borradores escritos por
 * Claude con búsqueda web y la PUERTA 0 (tu revisión). Al aprobar un
 * borrador se crea la Story por el mismo camino que POST /stories, y de ahí
 * en adelante el pipeline sigue exactamente igual (/tiers, PUERTA 1, ...).
 *
 * Mismo patrón que StoryController: cargar -> un método de
 * StoryDraftService -> guardar -> devolver. Ningún endpoint de acá toca
 * Higgsfield ni ElevenLabs.
 *
 * <pre>
 * POST   /story-drafts/proposals        5 temas candidatos (no guarda nada)
 * POST   /story-drafts                  generar un borrador (queda PENDING_REVIEW)
 * GET    /story-drafts                  resumen de todos los borradores
 * GET    /story-drafts/{id}             borrador completo
 * POST   /story-drafts/{id}/decision    APPROVE | REJECT          (PUERTA 0)
 * </pre>
 */
@RestController
@RequestMapping("/story-drafts")
public class StoryDraftController {

    private static final Logger log = LoggerFactory.getLogger(StoryDraftController.class);

    private final StoryDraftService draftService;
    private final JsonFileDraftRepository draftRepository;
    private final StoryRepository storyRepository;
    private final StoryIndex storyIndex;

    public StoryDraftController(
            StoryDraftService draftService,
            JsonFileDraftRepository draftRepository,
            StoryRepository storyRepository,
            StoryIndex storyIndex
    ) {
        this.draftService = draftService;
        this.draftRepository = draftRepository;
        this.storyRepository = storyRepository;
        this.storyIndex = storyIndex;
    }

    @PostMapping("/proposals")
    public Map<String, Object> propose(@RequestBody(required = false) Map<String, Object> body) {
        String focus = optionalString(body, "focus");
        return draftService.propose(focus, existingStories());
    }

    @PostMapping
    public ResponseEntity<StoryDraft> generate(@RequestBody(required = false) Map<String, Object> body) {
        String topic = optionalString(body, "topic");
        if (topic == null || topic.isBlank()) {
            throw new ApiError(400, "Falta el campo requerido 'topic'.");
        }
        StoryDraft rejected = null;
        String rejectedDraftId = optionalString(body, "rejectedDraftId");
        if (rejectedDraftId != null) {
            rejected = loadDraftOrNotFound(rejectedDraftId);
            if (rejected.status != DraftStatus.REJECTED) {
                throw new ApiError(409, "El borrador '" + rejectedDraftId + "' no está REJECTED (" + rejected.status
                        + "); solo se regenera a partir de un borrador rechazado.");
            }
        }
        Set<String> takenIds = new HashSet<>(storyIndex.storyIds());
        takenIds.addAll(draftRepository.listIds());

        StoryDraft draft = draftService.generate(topic.strip(), rejected,
                new StoryDraftService.DraftContext(existingStories(), takenIds));
        draftRepository.create(draft);
        return ResponseEntity.status(HttpStatus.CREATED).body(draft);
    }

    @GetMapping
    public List<Map<String, Object>> listDrafts() {
        return draftRepository.summaries();
    }

    @GetMapping("/{id}")
    public StoryDraft getDraft(@PathVariable("id") String id) {
        return loadDraftOrNotFound(id);
    }

    @PostMapping("/{id}/decision")
    public ResponseEntity<Map<String, Object>> decide(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body) {
        StoryDraft draft = loadDraftOrNotFound(id);
        DraftDecision decision = parseDecision(body != null ? body : Map.of());

        StoryDraftService.DecisionOutcome outcome = draftService.decide(
                draft, decision, existingStories(), storyIndex.storyExists(draft.id));

        Story story = outcome.story();
        if (story != null) {
            storyRepository.save(story);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            draftRepository.update(outcome.draft());
        } catch (RuntimeException e) {
            if (story == null) {
                throw e;
            }
            log.error("La Story '{}' YA FUE CREADA, pero no se pudo marcar el borrador '{}' como APPROVED. "
                    + "No vuelvas a aprobarlo: corrige el archivo del borrador a mano.", story.id(), draft.id, e);
            response.put("error", "La Story '" + story.id() + "' ya fue creada, pero no se pudo guardar el borrador como APPROVED: "
                    + e.getMessage());
            response.put("storyCreated", true);
            response.put("story", SnapshotMapper.toMap(story.toSnapshot()));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
        response.put("draft", outcome.draft());
        response.put("story", story != null ? SnapshotMapper.toMap(story.toSnapshot()) : null);
        return ResponseEntity.ok(response);
    }

    // --- helpers ----------------------------------------------------------------------------

    private StoryDraft loadDraftOrNotFound(String draftId) {
        if (!DraftText.isValidId(draftId)) {
            throw new ApiError(400, "Id de borrador inválido: '" + draftId + "' (minúsculas, dígitos y guiones, máximo "
                    + DraftText.MAX_ID_LENGTH + " caracteres).");
        }
        return draftRepository.findById(draftId)
                .orElseThrow(() -> new ApiError(404, "No existe un borrador con id '" + draftId + "'."));
    }

    /** Historias existentes y borradores no rechazados (sin repetir id): lo que el generador no debe repetir. */
    private List<ExistingStory> existingStories() {
        List<ExistingStory> existing = new ArrayList<>(storyIndex.existingStories());
        Set<String> seen = new HashSet<>();
        existing.forEach(e -> seen.add(e.id()));
        for (StoryDraft draft : draftRepository.findAllReadable()) {
            if (draft.status != DraftStatus.REJECTED && seen.add(draft.id)) {
                existing.add(new ExistingStory(draft.id, draft.title, draft.topic));
            }
        }
        return existing;
    }

    private static DraftDecision parseDecision(Map<String, Object> body) {
        Object decisionValue = body.get("decision");
        if (!(decisionValue instanceof String decisionText)) {
            throw new ApiError(400, "Falta 'decision' (APPROVE o REJECT).");
        }
        Decision decision;
        try {
            decision = Decision.valueOf(decisionText.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiError(400, "'decision' debe ser APPROVE o REJECT, llegó: '" + decisionText + "'.");
        }
        Object ack = body.get("acknowledgeUnverified");
        if (ack != null && !(ack instanceof Boolean)) {
            throw new ApiError(400, "'acknowledgeUnverified' debe ser true o false.");
        }
        return new DraftDecision(
                decision,
                optionalString(body, "note"),
                parseEdits(body, "narrationEdits", "narrationText"),
                parseEdits(body, "promptEdits", "visualPrompt"),
                Boolean.TRUE.equals(ack)
        );
    }

    private static List<DraftDecision.SceneTextEdit> parseEdits(Map<String, Object> body, String field, String textField) {
        Object value = body.get(field);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new ApiError(400, "'" + field + "' debe ser una lista de {sceneId, " + textField + "}.");
        }
        List<DraftDecision.SceneTextEdit> edits = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map) || !(map.get("sceneId") instanceof String sceneId)
                    || !(map.get(textField) instanceof String text)) {
                throw new ApiError(400, "Cada elemento de '" + field + "' debe ser {sceneId, " + textField + "} con texto.");
            }
            edits.add(new DraftDecision.SceneTextEdit(sceneId, text));
        }
        return edits;
    }

    private static String optionalString(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return null;
        }
        if (!(body.get(key) instanceof String value)) {
            throw new ApiError(400, "'" + key + "' debe ser texto.");
        }
        return value;
    }
}
