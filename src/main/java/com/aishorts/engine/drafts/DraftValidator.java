package com.aishorts.engine.drafts;

import com.aishorts.engine.domain.SceneRole;
import com.aishorts.engine.drafts.StoryDraft.Claim;
import com.aishorts.engine.drafts.StoryDraft.DraftScene;
import com.aishorts.engine.drafts.StoryDraft.Source;
import com.aishorts.engine.drafts.StoryDraft.WhatIf;
import com.aishorts.engine.drafts.StoryDraft.WhatIfParameter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reglas del borrador (HARD y SOFT), como función pura: recibe un borrador
 * ya recalculado por DraftNormalizer y el contexto (ids ocupados, historias
 * existentes, URLs que de verdad devolvió la búsqueda) y devuelve la lista
 * de violaciones. No modifica nada ni hace I/O.
 *
 * Cada escena y cada claim se evalúa dentro de su propio try/catch: algo
 * mal formado produce una violación SCENE_MALFORMED/CLAIM_MALFORMED, nunca
 * una excepción que corte la validación del resto.
 */
public final class DraftValidator {

    /** Lo que la validación necesita saber del mundo, además del borrador. */
    public record Context(Set<String> takenIds, Collection<ExistingStory> existingStories, Collection<String> researchedUrls) {
        public Context {
            takenIds = takenIds != null ? takenIds : Set.of();
            existingStories = existingStories != null ? existingStories : List.of();
            researchedUrls = researchedUrls != null ? researchedUrls : List.of();
        }
    }

    private final DraftsProperties properties;
    private final SourcePolicy sourcePolicy;
    private final Map<String, Pattern> forbiddenTerms;
    private final Map<String, Pattern> negativeTerms;

    public DraftValidator(DraftsProperties properties, SourcePolicy sourcePolicy) {
        this.properties = properties;
        this.sourcePolicy = sourcePolicy;
        this.forbiddenTerms = compileTerms(properties.forbiddenVisualTerms());
        this.negativeTerms = compileTerms(properties.negativePromptTerms());
    }

    public List<Violation> validate(StoryDraft draft, Context context) {
        List<Violation> violations = new ArrayList<>();
        Set<String> researched = DraftRules.canonicalUrls(context.researchedUrls());
        Map<String, Claim> claimsById = DraftRules.claimsById(draft);

        validateSlug(draft, context, violations);
        validateScenes(draft, claimsById, violations);
        validateWhatIf(draft, claimsById, violations);
        validateSources(draft, researched, violations);
        validateDuplicateTopic(draft, context, violations);
        return violations;
    }

    public static List<Violation> hardOnly(List<Violation> violations) {
        return violations.stream().filter(Violation::isHard).toList();
    }

    // --- HARD: slug ------------------------------------------------------------------------

    private void validateSlug(StoryDraft draft, Context context, List<Violation> violations) {
        if (!DraftText.isValidId(draft.id)) {
            violations.add(Violation.hard("SLUG_INVALID", "El id '" + draft.id + "' no es un slug válido "
                    + "(minúsculas, dígitos y guiones, máximo " + DraftText.MAX_ID_LENGTH + " caracteres)."));
        } else if (context.takenIds().contains(draft.id)) {
            violations.add(Violation.hard("SLUG_TAKEN", "Ya existe una historia o un borrador con id '" + draft.id + "'."));
        }
        if (isBlank(draft.topic) || isBlank(draft.title)) {
            violations.add(Violation.hard("TOPIC_TITLE_MISSING", "Faltan 'topic' o 'title' (la Story los exige)."));
        }
    }

    // --- HARD y SOFT por escena ------------------------------------------------------------

    private void validateScenes(StoryDraft draft, Map<String, Claim> claimsById, List<Violation> violations) {
        List<DraftScene> scenes = DraftRules.scenes(draft);
        if (scenes.size() != DraftRules.EXPECTED_ROLES.size()) {
            violations.add(Violation.hard("SCENE_COUNT", "Se esperaban exactamente " + DraftRules.EXPECTED_ROLES.size()
                    + " escenas y hay " + scenes.size() + "."));
        }

        int totalWords = 0;
        for (int i = 0; i < scenes.size(); i++) {
            DraftScene scene = scenes.get(i);
            String sceneId = scene != null ? scene.id : null;
            try {
                totalWords += validateScene(draft, scene, i, claimsById, violations);
            } catch (RuntimeException e) {
                violations.add(Violation.hardScene("SCENE_MALFORMED", sceneId,
                        "La escena en la posición " + (i + 1) + " no se pudo validar: " + e));
            }
        }

        if (totalWords < properties.minTotalWords() || totalWords > properties.maxTotalWords()) {
            violations.add(Violation.hard("TOTAL_WORDS", "El total es de " + totalWords + " palabras; debe estar entre "
                    + properties.minTotalWords() + " y " + properties.maxTotalWords() + "."));
        }
    }

    /** Valida una escena y devuelve sus palabras (para el total). */
    private int validateScene(StoryDraft draft, DraftScene scene, int index, Map<String, Claim> claimsById,
                              List<Violation> violations) {
        Objects.requireNonNull(scene, "escena null");
        SceneRole expected = index < DraftRules.EXPECTED_ROLES.size() ? DraftRules.EXPECTED_ROLES.get(index) : null;
        SceneRole role = DraftRules.roleOf(scene);
        String where = "Escena " + (index + 1) + " (" + scene.role + ")";

        if (expected == null || role != expected) {
            violations.add(Violation.hardScene("SCENE_ROLE", scene.id, where + ": en esta posición se esperaba "
                    + (expected != null ? expected : "ninguna escena") + "."));
        }
        if (scene.order == null || scene.order != index + 1) {
            violations.add(Violation.hardScene("SCENE_ORDER", scene.id, where + ": 'order' es " + scene.order
                    + " y debería ser " + (index + 1) + "."));
        }
        if (role != null && DraftText.isValidId(draft.id)) {
            String expectedId = draft.id + "-" + role.name().toLowerCase(Locale.ROOT);
            if (!expectedId.equals(scene.id)) {
                violations.add(Violation.hardScene("SCENE_ID", scene.id, where + ": el id debería ser '" + expectedId + "'."));
            }
        }
        if (isBlank(scene.narrationText) || isBlank(scene.visualPrompt)) {
            violations.add(Violation.hardScene("SCENE_TEXT_MISSING", scene.id, where + ": falta narrationText o visualPrompt."));
        }

        double seconds = scene.words / properties.wordsPerSecond();
        if (seconds < properties.minSceneSeconds() || seconds > properties.maxSceneSeconds()) {
            violations.add(Violation.hardScene("SCENE_DURATION", scene.id, where + ": " + scene.words + " palabras ≈ "
                    + String.format(Locale.ROOT, "%.1f", seconds) + " s; cada escena debe durar entre "
                    + properties.minSceneSeconds() + " y " + properties.maxSceneSeconds() + " s."));
        }

        List<String> sceneClaimIds = DraftRules.claimIds(scene);
        for (String claimId : sceneClaimIds) {
            if (!claimsById.containsKey(claimId)) {
                violations.add(Violation.hardScene("CLAIM_REF_MISSING", scene.id, where + ": referencia la claim '"
                        + claimId + "', que no existe."));
            }
        }
        if (DraftRules.isReal(role)) {
            if (sceneClaimIds.isEmpty()) {
                violations.add(Violation.hardScene("REAL_SCENE_NO_CLAIMS", scene.id, where
                        + ": las escenas REAL (1-3) necesitan claimIds."));
            }
            for (String claimId : sceneClaimIds) {
                Claim claim = claimsById.get(claimId);
                if (claim != null && (claim.type == null || !DraftRules.REAL_CLAIM_TYPES.contains(claim.type))) {
                    violations.add(Violation.hardScene("REAL_CLAIM_TYPE", scene.id, where + ": la claim '" + claimId
                            + "' es de tipo " + claim.type + "; en escenas REAL debe ser HECHO o ESTIMACION."));
                }
            }
        }
        if (DraftRules.isHypothetical(role)) {
            for (String claimId : sceneClaimIds) {
                Claim claim = claimsById.get(claimId);
                if (claim != null && !DraftRules.isHypothesis(claim)) {
                    violations.add(Violation.hardScene("IF_CLAIM_TYPE", scene.id, where + ": la claim '" + claimId
                            + "' es de tipo " + claim.type + "; en escenas IF debe ser HIPOTESIS."));
                }
            }
        }

        String prompt = scene.visualPrompt != null ? scene.visualPrompt : "";
        forbiddenTerms.forEach((term, pattern) -> {
            if (pattern.matcher(prompt).find()) {
                violations.add(Violation.hardScene("FORBIDDEN_VISUAL_TERM", scene.id, where
                        + ": el visualPrompt contiene la palabra prohibida '" + term + "'."));
            }
        });
        negativeTerms.forEach((term, pattern) -> {
            if (pattern.matcher(prompt).find()) {
                violations.add(Violation.hardScene("NEGATIVE_PROMPT", scene.id, where
                        + ": el visualPrompt contiene un negativo ('" + term + "'); el modelo de video no tiene prompt negativo."));
            }
        });

        // SOFT
        DraftsProperties.WordRange range = role != null ? properties.sceneWordRanges().get(role.name()) : null;
        if (range != null && (scene.words < range.min() || scene.words > range.max())) {
            violations.add(Violation.softScene("SCENE_WORDS_RANGE", scene.id, where + ": " + scene.words
                    + " palabras; la tabla de reglas sugiere " + range.min() + "-" + range.max() + "."));
        }
        String anchor = properties.styleAnchor();
        if (anchor != null && !anchor.isBlank()
                && !prompt.trim().toLowerCase(Locale.ROOT).startsWith(anchor.trim().toLowerCase(Locale.ROOT))) {
            violations.add(Violation.softScene("STYLE_ANCHOR", scene.id, where
                    + ": el visualPrompt no empieza con el ancla de estilo \"" + anchor + "\"."));
        }
        return scene.words;
    }

    // --- HARD: what-if ---------------------------------------------------------------------

    private void validateWhatIf(StoryDraft draft, Map<String, Claim> claimsById, List<Violation> violations) {
        WhatIf whatIf = draft.whatIf;
        List<WhatIfParameter> params = whatIf == null || whatIf.parametersFromReal == null ? List.of() : whatIf.parametersFromReal;
        if (whatIf == null || isBlank(whatIf.premise) || params.isEmpty()) {
            violations.add(Violation.hard("WHATIF_MISSING",
                    "Las escenas IF (4-5) necesitan whatIf con 'premise' y 'parametersFromReal' no vacíos."));
        }
        for (WhatIfParameter param : params) {
            String claimId = param != null ? param.claimId : null;
            if (claimId == null || !claimsById.containsKey(claimId)) {
                violations.add(Violation.hardClaim("WHATIF_CLAIM_REF_MISSING", claimId,
                        "whatIf.parametersFromReal referencia la claim '" + claimId + "', que no existe."));
            }
        }
    }

    // --- SOFT: fuentes ---------------------------------------------------------------------

    private void validateSources(StoryDraft draft, Set<String> researched, List<Violation> violations) {
        Set<String> realClaimIds = DraftRules.realClaimIds(draft);
        for (Claim claim : DraftRules.claims(draft)) {
            String claimId = claim != null ? claim.id : null;
            try {
                Objects.requireNonNull(claim, "claim null");
                for (Source source : DraftRules.sources(claim)) {
                    if (source == null || source.url == null) {
                        continue;
                    }
                    if (!DraftRules.isResearched(source.url, researched)) {
                        violations.add(Violation.softClaim("SOURCE_NOT_RESEARCHED", claimId, "La URL " + source.url
                                + " de la claim '" + claimId + "' no aparece entre los resultados de búsqueda: la claim queda SIN_VERIFICAR."));
                    }
                    if (sourcePolicy.tierOf(source.url) == null && !sourcePolicy.isOrientationOnly(source.url)) {
                        violations.add(Violation.softClaim("UNKNOWN_DOMAIN_TIER", claimId, "El dominio de " + source.url
                                + " (claim '" + claimId + "') no está en las listas de tier A ni B."));
                    }
                }
                if (realClaimIds.contains(claimId)
                        && DraftRules.independentTierABDomains(claim, researched, sourcePolicy) < 2) {
                    String reason = DraftRules.onlyOrientationSources(claim, sourcePolicy)
                            ? "solo está respaldada por Wikipedia"
                            : "tiene menos de 2 fuentes tier A/B consultadas de dominios distintos";
                    violations.add(Violation.softClaim("WEAK_SOURCES", claimId, "La claim '" + claimId + "' " + reason
                            + ": confianza forzada a BAJA."));
                }
            } catch (RuntimeException e) {
                violations.add(Violation.hardClaim("CLAIM_MALFORMED", claimId, "La claim '" + claimId
                        + "' no se pudo validar: " + e));
            }
        }
    }

    // --- SOFT: tema repetido ---------------------------------------------------------------

    private void validateDuplicateTopic(StoryDraft draft, Context context, List<Violation> violations) {
        for (ExistingStory existing : context.existingStories()) {
            if (existing == null || Objects.equals(existing.id(), draft.id)) {
                continue;
            }
            boolean overlaps = DraftText.topicsOverlap(draft.topic, existing.topic())
                    || DraftText.topicsOverlap(draft.title, existing.title())
                    || DraftText.topicsOverlap(draft.topic, existing.title())
                    || DraftText.topicsOverlap(draft.title, existing.topic());
            if (overlaps) {
                violations.add(Violation.soft("DUPLICATE_TOPIC", "El tema o título se parece a la historia existente '"
                        + existing.id() + "' (" + existing.topic() + ")."));
            }
        }
    }

    // --- helpers ---------------------------------------------------------------------------

    /**
     * Cada término se busca como palabra completa, sin distinguir
     * mayúsculas; los espacios internos aceptan cualquier espacio y el
     * apóstrofo acepta también el tipográfico.
     */
    private static Map<String, Pattern> compileTerms(List<String> terms) {
        Map<String, Pattern> compiled = new java.util.LinkedHashMap<>();
        for (String term : terms) {
            if (term == null || term.isBlank()) {
                continue;
            }
            StringBuilder regex = new StringBuilder("(?<![\\p{L}\\p{N}])");
            for (String piece : term.trim().split("\\s+")) {
                if (regex.length() > "(?<![\\p{L}\\p{N}])".length()) {
                    regex.append("\\s+");
                }
                regex.append(Pattern.quote(piece).replace("'", "\\E['’]\\Q"));
            }
            regex.append("(?![\\p{L}\\p{N}])");
            compiled.put(term.trim(), Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        }
        return compiled;
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
