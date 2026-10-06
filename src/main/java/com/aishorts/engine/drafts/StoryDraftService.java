package com.aishorts.engine.drafts;

import com.aishorts.engine.approval.Decision;
import com.aishorts.engine.domain.Story;
import com.aishorts.engine.drafts.StoryDraft.DraftScene;
import com.aishorts.engine.drafts.StoryDraftGenerator.GeneratorRequest;
import com.aishorts.engine.drafts.StoryDraftGenerator.GeneratorResult;
import com.aishorts.engine.drafts.StoryDraftGenerator.Mode;
import com.aishorts.engine.duration.DurationBudget;
import com.aishorts.engine.duration.NarrationDurationEstimator;
import com.aishorts.engine.script.SceneDraft;
import com.aishorts.engine.script.ScriptDraftingService;
import com.aishorts.engine.script.StoryBrief;
import com.aishorts.engine.script.StoryDraftingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * El paso PREVIO al pipeline: propuestas de tema, borrador con búsqueda
 * web y la PUERTA 0 (revisión humana). Un método por endpoint, como
 * StoryApprovalService: no sabe nada de repositorios ni de HTTP — recibe lo
 * que el controller ya cargó y devuelve lo que el controller guarda.
 *
 * Nada de esta clase llama a Higgsfield ni a ElevenLabs. Al aprobar un
 * borrador, la Story se arma con el MISMO método que usa POST /stories
 * (StoryDraftingService.draftStory), pero con un ScriptDraftingService que
 * devuelve las escenas ya revisadas en vez de llamar a Claude otra vez.
 */
public final class StoryDraftService {

    /** Rango que se le pasa al StoryBrief al aprobar; StoryDraftingService no lo usa para armar la Story. */
    private static final DurationBudget PIPELINE_BUDGET = DurationBudget.ofSeconds(35, 40);
    private static final int CANDIDATE_COUNT = 5;

    private final StoryDraftGenerator generator;
    private final DraftNormalizer normalizer;
    private final DraftValidator validator;
    private final DraftPromptBuilder prompts;
    private final DraftsProperties properties;
    private final NarrationDurationEstimator pipelineEstimator;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public StoryDraftService(
            StoryDraftGenerator generator,
            DraftNormalizer normalizer,
            DraftValidator validator,
            DraftPromptBuilder prompts,
            DraftsProperties properties,
            NarrationDurationEstimator pipelineEstimator,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.generator = generator;
        this.normalizer = normalizer;
        this.validator = validator;
        this.prompts = prompts;
        this.properties = properties;
        this.pipelineEstimator = pipelineEstimator;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Lo que ya existe y el generador debe respetar: temas para no repetir e ids ocupados (stories + drafts). */
    public record DraftContext(List<ExistingStory> existingStories, Set<String> takenIds) {
        public DraftContext {
            existingStories = existingStories != null ? List.copyOf(existingStories) : List.of();
            takenIds = takenIds != null ? Set.copyOf(takenIds) : Set.of();
        }
    }

    public record DecisionOutcome(StoryDraft draft, Story story) {
    }

    // --- POST /story-drafts/proposals --------------------------------------------------------

    public Map<String, Object> propose(String focus, List<ExistingStory> existingStories) {
        boolean webSearch = properties.webSearch().enabled();
        int maxUses = properties.webSearch().maxUsesProposals();
        String system = prompts.systemPrompt("PROPUESTAS", today(), existingStories, webSearch, maxUses);
        GeneratorResult result = generator.generate(
                new GeneratorRequest(Mode.PROPOSALS, system, prompts.proposalsUserMessage(focus), webSearch, maxUses));

        JsonNode root = normalizer.extractJsonObject(result.text());
        if (root == null) {
            throw new DraftException(502, "Claude no devolvió un JSON válido para las propuestas.");
        }
        JsonNode candidatesNode = root.get("candidates");
        if (candidatesNode == null || !candidatesNode.isArray()) {
            throw new DraftException(502, "La respuesta de Claude no trae la lista 'candidates'.");
        }
        if (candidatesNode.size() != CANDIDATE_COUNT) {
            throw new DraftException(502, "Claude devolvió " + candidatesNode.size() + " candidatos; se esperaban exactamente "
                    + CANDIDATE_COUNT + ".");
        }

        List<Map<String, Object>> candidates = new ArrayList<>();
        for (int i = 0; i < candidatesNode.size(); i++) {
            candidates.add(toCandidate(candidatesNode.get(i), i, existingStories));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("candidates", candidates);
        response.put("researchedUrls", result.researchedUrls());
        response.put("usage", result.usage());
        return response;
    }

    private Map<String, Object> toCandidate(JsonNode node, int index, List<ExistingStory> existingStories) {
        String where = "Candidato " + (index + 1);
        if (node == null || !node.isObject()) {
            throw new DraftException(502, where + " no es un objeto JSON.");
        }
        Map<String, Object> candidate = new LinkedHashMap<>();
        for (String field : List.of("topic", "realEvent", "whatIf")) {
            JsonNode value = node.get(field);
            if (value == null || !value.isTextual() || value.asText().isBlank()) {
                throw new DraftException(502, where + ": falta el campo de texto '" + field + "'.");
            }
            candidate.put(field, value.asText().strip());
        }
        for (String field : List.of("keyFigures", "sourcesAvailable")) {
            JsonNode value = node.get(field);
            if (value == null || !value.isArray()) {
                throw new DraftException(502, where + ": falta la lista '" + field + "'.");
            }
            candidate.put(field, objectMapper.convertValue(value, Object.class));
        }
        JsonNode risks = node.get("risks");
        candidate.put("risks", risks != null && risks.isArray() ? objectMapper.convertValue(risks, Object.class) : List.of());

        String topic = (String) candidate.get("topic");
        List<String> overlapsWith = existingStories.stream()
                .filter(e -> DraftText.topicsOverlap(topic, e.topic()) || DraftText.topicsOverlap(topic, e.title()))
                .map(ExistingStory::id)
                .toList();
        // el valor del modelo se ignora: lo decide el servidor
        candidate.put("overlapWithExisting", !overlapsWith.isEmpty());
        candidate.put("overlapsWith", overlapsWith);
        return candidate;
    }

    // --- POST /story-drafts ------------------------------------------------------------------

    /**
     * Genera un borrador: una pasada con búsqueda web y, si quedan
     * violaciones HARD, UN reintento sin herramientas con el JSON anterior y
     * las violaciones como feedback. El resultado se devuelve siempre (con
     * sus violaciones visibles) para que quien revisa decida; el id final
     * nunca choca con una historia o borrador existentes.
     */
    public StoryDraft generate(String topic, StoryDraft rejectedDraft, DraftContext context) {
        LocalDate today = today();
        boolean webSearch = properties.webSearch().enabled();
        int maxUses = properties.webSearch().maxUsesDraft();

        String system = prompts.systemPrompt("BORRADOR", today, context.existingStories(), webSearch, maxUses);
        GeneratorResult first = generator.generate(new GeneratorRequest(
                Mode.DRAFT, system, prompts.draftUserMessage(topic, rejectedDraft), webSearch, maxUses));
        Set<String> researched = new LinkedHashSet<>(first.researchedUrls());
        DraftUsage usage = first.usage();
        Attempt attempt = evaluate(first.text(), researched, context, today);
        int attempts = 1;

        if (!attempt.hardViolations().isEmpty()) {
            String retrySystem = prompts.systemPrompt("BORRADOR", today, context.existingStories(), false, 0);
            String retryUser = prompts.retryUserMessage(topic, rejectedDraft, first.text(), attempt.hardViolations());
            GeneratorResult second = generator.generate(new GeneratorRequest(Mode.DRAFT_RETRY, retrySystem, retryUser, false, 0));
            researched.addAll(second.researchedUrls());
            usage = usage.plus(second.usage());
            attempt = evaluate(second.text(), researched, context, today);
            attempts = 2;
        }

        StoryDraft draft = attempt.draft();
        List<Violation> violations = new ArrayList<>(attempt.violations());
        Violation renamed = ensureFreeValidId(draft, topic, context.takenIds());
        if (renamed != null) {
            violations = attempt.jsonInvalid()
                    ? new ArrayList<>(attempt.parseViolations())
                    : revalidate(draft, attempt.parseViolations(), researched, context);
            violations.add(renamed);
        }

        draft.status = DraftStatus.PENDING_REVIEW;
        draft.attempts = attempts;
        draft.requestedTopic = topic;
        draft.rejectedDraftId = rejectedDraft != null ? rejectedDraft.id : null;
        draft.reviewerFeedback = rejectedDraft != null ? rejectedDraft.rejectionNote : null;
        draft.createdAt = OffsetDateTime.now(clock).toString();
        draft.researchedUrls = new ArrayList<>(researched);
        draft.usage = usage;
        setViolations(draft, violations);
        return draft;
    }

    private record Attempt(StoryDraft draft, List<Violation> parseViolations, List<Violation> violations, boolean jsonInvalid) {
        List<Violation> hardViolations() {
            return DraftValidator.hardOnly(violations);
        }
    }

    private Attempt evaluate(String text, Set<String> researched, DraftContext context, LocalDate today) {
        DraftNormalizer.Parsed parsed = normalizer.parse(text);
        StoryDraft draft = parsed.draft();
        if (parsed.jsonInvalid()) {
            return new Attempt(draft, parsed.violations(), parsed.violations(), true);
        }
        normalizer.recalculate(draft, researched, today);
        return new Attempt(draft, parsed.violations(), revalidate(draft, parsed.violations(), researched, context), false);
    }

    private List<Violation> revalidate(StoryDraft draft, List<Violation> parseViolations, Set<String> researched, DraftContext context) {
        List<Violation> violations = new ArrayList<>(parseViolations);
        violations.addAll(validator.validate(draft,
                new DraftValidator.Context(context.takenIds(), context.existingStories(), researched)));
        return violations;
    }

    /**
     * Si el id del modelo es inválido o ya está ocupado, asigna uno libre
     * (slug-2, slug-3, ...) y re-prefija los ids de escena. Devuelve la
     * violación SOFT que lo deja visible, o null si no hizo falta.
     */
    private static Violation ensureFreeValidId(StoryDraft draft, String requestedTopic, Set<String> takenIds) {
        boolean invalid = !DraftText.isValidId(draft.id);
        if (!invalid && !takenIds.contains(draft.id)) {
            return null;
        }
        String base = invalid ? DraftText.slugify(draft.topic != null ? draft.topic : requestedTopic) : draft.id;
        String newId = freeId(base, takenIds);
        String previous = draft.id;
        draft.id = newId;
        for (DraftScene scene : DraftRules.scenes(draft)) {
            if (scene != null && DraftRules.roleOf(scene) != null) {
                scene.id = newId + "-" + DraftRules.roleOf(scene).name().toLowerCase(Locale.ROOT);
            }
        }
        return invalid
                ? Violation.soft("SLUG_REASSIGNED", "El id '" + previous + "' no era un slug válido; el servidor asignó '" + newId + "'.")
                : Violation.soft("SLUG_RENAMED", "El id '" + previous + "' ya estaba ocupado; el servidor lo renombró a '" + newId
                        + "' (no se sobrescribe nada).");
    }

    static String freeId(String base, Set<String> takenIds) {
        if (!takenIds.contains(base)) {
            return base;
        }
        for (int n = 2; ; n++) {
            String suffix = "-" + n;
            String prefix = base.length() + suffix.length() > DraftText.MAX_ID_LENGTH
                    ? base.substring(0, DraftText.MAX_ID_LENGTH - suffix.length()).replaceAll("-+$", "")
                    : base;
            String candidate = prefix + suffix;
            if (!takenIds.contains(candidate)) {
                return candidate;
            }
        }
    }

    // --- POST /story-drafts/{id}/decision (PUERTA 0) -----------------------------------------

    /**
     * Aplica tu decisión. REJECT solo marca el borrador. APPROVE aplica las
     * ediciones, recalcula y revalida (las violaciones HARD bloquean con
     * 422), exige acknowledgeUnverified si quedan claims sin verificar o de
     * confianza BAJA, y arma la Story por el mismo camino que POST /stories,
     * con SOLO los campos del pipeline. Ni guarda ni lee nada: el
     * controller guarda la Story y después el borrador.
     */
    public DecisionOutcome decide(StoryDraft draft, DraftDecision decision, List<ExistingStory> existingStories, boolean storyExists) {
        if (draft.status != DraftStatus.PENDING_REVIEW) {
            throw new DraftException(409, "El borrador '" + draft.id + "' ya fue decidido (" + draft.status + ").");
        }
        Map<String, DraftScene> scenesById = new HashMap<>();
        for (DraftScene scene : DraftRules.scenes(draft)) {
            if (scene != null && scene.id != null) {
                scenesById.putIfAbsent(scene.id, scene);
            }
        }
        checkEdits(decision.narrationEdits(), scenesById, "narrationEdits");
        checkEdits(decision.promptEdits(), scenesById, "promptEdits");

        if (decision.decision() == Decision.REJECT) {
            if (decision.note() == null || decision.note().isBlank()) {
                throw new DraftException(400, "Para rechazar un borrador hace falta una nota ('note') no vacía.");
            }
            draft.status = DraftStatus.REJECTED;
            draft.rejectionNote = decision.note().strip();
            draft.decidedAt = OffsetDateTime.now(clock).toString();
            return new DecisionOutcome(draft, null);
        }

        for (DraftDecision.SceneTextEdit edit : decision.narrationEdits()) {
            scenesById.get(edit.sceneId()).narrationText = edit.text();
        }
        for (DraftDecision.SceneTextEdit edit : decision.promptEdits()) {
            scenesById.get(edit.sceneId()).visualPrompt = edit.text();
        }
        normalizer.recalculate(draft, draft.researchedUrls, accessedDate(draft));
        // Al aprobar, el propio id del borrador no cuenta como ocupado; una Story existente se rechaza abajo con 409.
        List<Violation> violations = validator.validate(draft,
                new DraftValidator.Context(Set.of(), existingStories, draft.researchedUrls));
        List<Violation> hard = DraftValidator.hardOnly(violations);
        if (!hard.isEmpty()) {
            throw new DraftException(422, "El borrador tiene " + hard.size() + " violaciones duras; no se puede aprobar.", hard);
        }
        List<String> unverified = DraftRules.unverifiedOrLowConfidenceClaimIds(draft);
        if (!unverified.isEmpty() && !decision.acknowledgeUnverified()) {
            throw new DraftException(422, "Hay claims SIN_VERIFICAR o con confianza BAJA " + unverified
                    + ": verifícalas a mano y reenvía la decisión con acknowledgeUnverified=true.");
        }
        if (storyExists) {
            throw new DraftException(409, "Ya existe una historia con id '" + draft.id + "'; no se sobrescribe.");
        }

        Story story = buildStory(draft);
        draft.status = DraftStatus.APPROVED;
        draft.approvedStoryId = story.id();
        draft.decidedAt = OffsetDateTime.now(clock).toString();
        setViolations(draft, violations);
        return new DecisionOutcome(draft, story);
    }

    private static void checkEdits(List<DraftDecision.SceneTextEdit> edits, Map<String, DraftScene> scenesById, String field) {
        for (DraftDecision.SceneTextEdit edit : edits) {
            if (edit == null || edit.sceneId() == null || !scenesById.containsKey(edit.sceneId())) {
                throw new DraftException(400, field + ": la escena '" + (edit != null ? edit.sceneId() : null)
                        + "' no existe en este borrador.");
            }
            if (edit.text() == null || edit.text().isBlank()) {
                throw new DraftException(400, field + ": el texto nuevo de la escena '" + edit.sceneId() + "' está vacío.");
            }
        }
    }

    /** Mismo camino que POST /stories: StoryDraftingService.draftStory, con las escenas revisadas en vez de Claude. */
    private Story buildStory(StoryDraft draft) {
        List<SceneDraft> sceneDrafts = DraftRules.scenes(draft).stream()
                .map(s -> new SceneDraft(DraftRules.roleOf(s), s.narrationText, s.visualPrompt))
                .toList();
        Set<String> realClaimIds = DraftRules.realClaimIds(draft);
        List<String> coreFacts = DraftRules.claims(draft).stream()
                .filter(c -> c != null && realClaimIds.contains(c.id) && c.text != null && !c.text.isBlank())
                .map(c -> c.text)
                .toList();
        if (coreFacts.isEmpty()) {
            coreFacts = List.of(draft.topic);
        }
        StoryBrief brief = new StoryBrief(draft.topic, draft.title, coreFacts, List.of(), PIPELINE_BUDGET);
        ScriptDraftingService reviewedScenes = ignored -> sceneDrafts;
        return new StoryDraftingService(reviewedScenes, pipelineEstimator).draftStory(draft.id, brief);
    }

    // --- helpers -----------------------------------------------------------------------------

    private static void setViolations(StoryDraft draft, List<Violation> violations) {
        draft.violations = new ArrayList<>(violations);
        if (draft.checks == null) {
            draft.checks = new StoryDraft.DraftChecks();
        }
        draft.checks.violations = violations.stream().map(v -> "[" + v.severity() + "] " + v.message()).toList();
    }

    /** La fecha de consulta de las fuentes es la del día en que se generó el borrador, no la de la aprobación. */
    private LocalDate accessedDate(StoryDraft draft) {
        if (draft.createdAt != null) {
            try {
                return OffsetDateTime.parse(draft.createdAt).toLocalDate();
            } catch (DateTimeParseException ignored) {
                // cae a hoy
            }
        }
        return today();
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
