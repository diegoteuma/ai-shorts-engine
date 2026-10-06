package com.aishorts.engine.drafts;

import com.aishorts.engine.drafts.StoryDraft.Claim;
import com.aishorts.engine.drafts.StoryDraft.DraftScene;
import com.aishorts.engine.drafts.StoryDraft.Source;
import com.aishorts.engine.drafts.StoryDraft.WhatIf;
import com.aishorts.engine.drafts.StoryDraft.WhatIfParameter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Convierte el texto de Claude en un StoryDraft y recalcula todo lo que el
 * servidor no le cree al modelo (palabras, segundos, tier y fecha de
 * consulta de cada fuente, estado y confianza de cada claim, checks).
 *
 * El parseo es tolerante a propósito: un campo mal formado produce una
 * violación HARD (con try/catch por escena y por claim) en vez de una
 * excepción, para que el borrador igual se pueda guardar y revisar.
 */
public final class DraftNormalizer {

    private static final int MAX_UNPARSED_CHARS = 20_000;

    private final DraftsProperties properties;
    private final SourcePolicy sourcePolicy;
    private final ObjectMapper objectMapper;

    public DraftNormalizer(DraftsProperties properties, SourcePolicy sourcePolicy, ObjectMapper objectMapper) {
        this.properties = properties;
        this.sourcePolicy = sourcePolicy;
        this.objectMapper = objectMapper;
    }

    public record Parsed(StoryDraft draft, List<Violation> violations) {
        public boolean jsonInvalid() {
            return violations.stream().anyMatch(v -> "JSON_INVALID".equals(v.code()));
        }
    }

    /**
     * Extrae el objeto JSON del texto del modelo (del primer '{' al último
     * '}', así tolera prosa o un bloque markdown alrededor). null si no hay
     * un objeto JSON parseable.
     */
    public JsonNode extractJsonObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(text.substring(start, end + 1));
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    public Parsed parse(String modelText) {
        StoryDraft draft = new StoryDraft();
        List<Violation> violations = new ArrayList<>();
        JsonNode root = extractJsonObject(modelText);
        if (root == null) {
            draft.unparsedResponse = truncate(modelText);
            violations.add(Violation.hard("JSON_INVALID", "La respuesta de Claude no contiene un objeto JSON válido."));
            return new Parsed(draft, violations);
        }

        draft.id = trimmedText(root, "id", violations);
        draft.topic = trimmedText(root, "topic", violations);
        draft.title = trimmedText(root, "title", violations);

        JsonNode scenesNode = root.get("scenes");
        if (scenesNode == null || !scenesNode.isArray()) {
            violations.add(Violation.hard("SCENES_MISSING", "'scenes' falta o no es una lista."));
        } else {
            for (int i = 0; i < scenesNode.size(); i++) {
                try {
                    draft.scenes.add(parseScene(scenesNode.get(i)));
                } catch (RuntimeException e) {
                    violations.add(Violation.hard("SCENE_MALFORMED",
                            "La escena en la posición " + (i + 1) + " está mal formada: " + e.getMessage()));
                }
            }
        }

        JsonNode claimsNode = root.get("claims");
        if (claimsNode != null && claimsNode.isArray()) {
            for (int i = 0; i < claimsNode.size(); i++) {
                try {
                    draft.claims.add(parseClaim(claimsNode.get(i)));
                } catch (RuntimeException e) {
                    violations.add(Violation.hard("CLAIM_MALFORMED",
                            "La claim en la posición " + (i + 1) + " está mal formada: " + e.getMessage()));
                }
            }
        }

        JsonNode whatIfNode = root.get("whatIf");
        if (whatIfNode != null && whatIfNode.isObject()) {
            try {
                draft.whatIf = parseWhatIf(whatIfNode);
            } catch (RuntimeException e) {
                violations.add(Violation.hard("WHATIF_MALFORMED", "'whatIf' está mal formado: " + e.getMessage()));
            }
        }

        draft.risks = stringList(root.get("risks"));
        // 'words' y 'checks' del modelo se ignoran a propósito: los recalcula recalculate().
        return new Parsed(draft, violations);
    }

    /**
     * Recalcula en el lugar todo lo derivado: palabras y segundos por
     * escena, fecha de consulta y tier de cada fuente, estado y confianza
     * de cada claim, y los checks agregados (sin checks.violations, que
     * completa quien valida).
     */
    public void recalculate(StoryDraft draft, Collection<String> researchedUrls, LocalDate today) {
        Set<String> researched = DraftRules.canonicalUrls(researchedUrls);
        double wordsPerSecond = properties.wordsPerSecond();

        int totalWords = 0;
        for (DraftScene scene : DraftRules.scenes(draft)) {
            if (scene == null) {
                continue;
            }
            scene.words = DraftText.countWords(scene.narrationText);
            scene.estimatedSeconds = round2(scene.words / wordsPerSecond);
            totalWords += scene.words;
        }

        Set<String> realClaimIds = DraftRules.realClaimIds(draft);
        for (Claim claim : DraftRules.claims(draft)) {
            if (claim == null) {
                continue;
            }
            for (Source source : DraftRules.sources(claim)) {
                if (source == null) {
                    continue;
                }
                source.accessed = today.toString();
                source.tier = sourcePolicy.tierOf(source.url);
            }
            List<Source> sources = DraftRules.sources(claim);
            boolean allResearched = !sources.isEmpty()
                    && sources.stream().allMatch(s -> s != null && DraftRules.isResearched(s.url, researched));
            boolean modelSaysVerified = claim.status != null && DraftRules.VERIFIED.equalsIgnoreCase(claim.status.trim());
            claim.status = modelSaysVerified && allResearched ? DraftRules.VERIFIED : DraftRules.UNVERIFIED;
            claim.type = upper(claim.type);
            claim.confidence = upper(claim.confidence);
            if (realClaimIds.contains(claim.id)
                    && DraftRules.independentTierABDomains(claim, researched, sourcePolicy) < 2) {
                claim.confidence = DraftRules.LOW_CONFIDENCE;
            }
        }

        StoryDraft.DraftChecks checks = new StoryDraft.DraftChecks();
        checks.totalWords = totalWords;
        checks.estimatedSeconds = round2(totalWords / wordsPerSecond);
        List<DraftScene> realScenes = DraftRules.scenes(draft).stream()
                .filter(s -> DraftRules.isReal(DraftRules.roleOf(s))).toList();
        checks.allFactsHaveClaims = realScenes.size() == DraftRules.REAL_ROLES.size()
                && realScenes.stream().allMatch(s -> !DraftRules.claimIds(s).isEmpty());
        List<Claim> realClaims = DraftRules.claims(draft).stream()
                .filter(c -> c != null && realClaimIds.contains(c.id)).toList();
        checks.sourcesVerified = !realClaims.isEmpty()
                && realClaims.stream().allMatch(c -> DraftRules.VERIFIED.equals(c.status));
        draft.checks = checks;
    }

    // --- parseo tolerante -----------------------------------------------------------------

    private DraftScene parseScene(JsonNode node) {
        requireObject(node);
        DraftScene scene = new DraftScene();
        scene.id = text(node, "id");
        scene.role = text(node, "role");
        if (scene.role != null) {
            scene.role = scene.role.trim().toUpperCase(Locale.ROOT);
        }
        scene.order = intOrNull(node, "order");
        scene.narrationText = text(node, "narrationText");
        scene.visualPrompt = text(node, "visualPrompt");
        scene.visualConstraints = stringList(node.get("visualConstraints"));
        scene.claimIds = stringList(node.get("claimIds"));
        return scene;
    }

    private Claim parseClaim(JsonNode node) {
        requireObject(node);
        Claim claim = new Claim();
        claim.id = text(node, "id");
        claim.type = text(node, "type");
        claim.text = text(node, "text");
        claim.value = text(node, "value");
        claim.range = text(node, "range");
        claim.status = text(node, "status");
        claim.confidence = text(node, "confidence");
        JsonNode disputed = node.get("disputed");
        claim.disputed = disputed != null && disputed.isBoolean() ? disputed.booleanValue() : null;
        claim.note = text(node, "note");
        JsonNode sources = node.get("sources");
        if (sources != null && sources.isArray()) {
            for (JsonNode sourceNode : sources) {
                requireObject(sourceNode);
                Source source = new Source();
                source.title = text(sourceNode, "title");
                source.publisher = text(sourceNode, "publisher");
                source.url = text(sourceNode, "url");
                claim.sources.add(source);
            }
        }
        return claim;
    }

    private WhatIf parseWhatIf(JsonNode node) {
        WhatIf whatIf = new WhatIf();
        whatIf.premise = text(node, "premise");
        whatIf.assumptions = stringList(node.get("assumptions"));
        whatIf.limits = text(node, "limits");
        JsonNode params = node.get("parametersFromReal");
        if (params != null && params.isArray()) {
            for (JsonNode paramNode : params) {
                requireObject(paramNode);
                WhatIfParameter param = new WhatIfParameter();
                param.name = text(paramNode, "name");
                param.value = text(paramNode, "value");
                param.claimId = text(paramNode, "claimId");
                whatIf.parametersFromReal.add(param);
            }
        }
        return whatIf;
    }

    private static void requireObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("se esperaba un objeto JSON");
        }
    }

    /** Valor de texto del campo; null si falta o es null. Un objeto o lista donde se esperaba texto es un error. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isValueNode()) {
            throw new IllegalArgumentException("'" + field + "' debería ser texto");
        }
        return value.asText();
    }

    private static String trimmedText(JsonNode node, String field, List<Violation> violations) {
        try {
            String value = text(node, field);
            return value == null ? null : value.trim();
        } catch (IllegalArgumentException e) {
            violations.add(Violation.hard("FIELD_MALFORMED", e.getMessage()));
            return null;
        }
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isIntegralNumber()) {
            return value.intValue();
        }
        if (value.isTextual()) {
            try {
                return Integer.parseInt(value.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isNull()) {
            return values;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item != null && item.isValueNode() && !item.isNull()) {
                    values.add(item.asText());
                }
            }
        } else if (node.isValueNode()) {
            values.add(node.asText());
        }
        return values;
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_UNPARSED_CHARS ? text : text.substring(0, MAX_UNPARSED_CHARS) + "…";
    }
}
