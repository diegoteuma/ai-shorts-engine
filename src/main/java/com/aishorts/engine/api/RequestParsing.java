package com.aishorts.engine.api;

import com.aishorts.engine.approval.CostDecision;
import com.aishorts.engine.approval.Decision;
import com.aishorts.engine.approval.PromptDecision;
import com.aishorts.engine.difficulty.DifficultyFactors;
import com.aishorts.engine.domain.GenerationTier;
import com.aishorts.engine.duration.DurationBudget;
import com.aishorts.engine.script.StoryBrief;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Convierte los Map/List sueltos que devuelve Json.parse en los records del
 * dominio que esperan los métodos de StoryApprovalService/StoryDraftingService.
 * Aislado acá para que ApiServer se lea como ruteo puro, no como parsing —
 * y para que un campo mal formado dé un error 400 claro, no una
 * ClassCastException genérica.
 */
final class RequestParsing {

    private RequestParsing() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, DifficultyFactors> parseDifficultyFactorsBySceneId(Map<String, Object> body) {
        Map<String, DifficultyFactors> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            if (!(entry.getValue() instanceof Map)) {
                throw new ApiError(400, "El valor para la escena '" + entry.getKey()
                        + "' debe ser un objeto con movementComplexity/elementCount/physicalRealism/cameraDynamism.");
            }
            Map<String, Object> factorsMap = (Map<String, Object>) entry.getValue();
            try {
                result.put(entry.getKey(), new DifficultyFactors(
                        intField(factorsMap, "movementComplexity"),
                        intField(factorsMap, "elementCount"),
                        intField(factorsMap, "physicalRealism"),
                        intField(factorsMap, "cameraDynamism")
                ));
            } catch (IllegalArgumentException e) {
                throw new ApiError(400, "Factores de dificultad inválidos para la escena '" + entry.getKey() + "': " + e.getMessage());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static List<PromptDecision> parsePromptDecisions(List<Object> body) {
        List<PromptDecision> decisions = new ArrayList<>();
        for (Object raw : body) {
            Map<String, Object> map = (Map<String, Object>) raw;
            String sceneId = stringField(map, "sceneId", true);
            Decision decision = parseDecision(stringField(map, "decision", true));
            String revisedNarrationText = stringField(map, "revisedNarrationText", false);
            String revisedVisualPrompt = stringField(map, "revisedVisualPrompt", false);
            String tierOverrideText = stringField(map, "tierOverride", false);
            GenerationTier tierOverride = tierOverrideText == null ? null : parseTier(tierOverrideText);
            String note = stringField(map, "note", false);
            decisions.add(new PromptDecision(sceneId, decision, revisedNarrationText, revisedVisualPrompt, tierOverride, note));
        }
        return decisions;
    }

    @SuppressWarnings("unchecked")
    static List<CostDecision> parseCostDecisions(List<Object> body) {
        List<CostDecision> decisions = new ArrayList<>();
        for (Object raw : body) {
            Map<String, Object> map = (Map<String, Object>) raw;
            String sceneId = stringField(map, "sceneId", true);
            Decision decision = parseDecision(stringField(map, "decision", true));
            String note = stringField(map, "note", false);
            decisions.add(new CostDecision(sceneId, decision, note));
        }
        return decisions;
    }

    @SuppressWarnings("unchecked")
    static StoryBrief parseStoryBrief(Map<String, Object> body) {
        String topic = stringField(body, "topic", true);
        String title = stringField(body, "title", true);
        List<String> coreFacts = stringList(body, "coreFacts", true);
        List<String> constraints = body.containsKey("constraints") ? stringList(body, "constraints", false) : List.of();
        if (!(body.get("durationBudget") instanceof Map)) {
            throw new ApiError(400, "Falta 'durationBudget' con 'minSeconds'/'maxSeconds'.");
        }
        Map<String, Object> budgetMap = (Map<String, Object>) body.get("durationBudget");
        long minSeconds = longField(budgetMap, "minSeconds");
        long maxSeconds = longField(budgetMap, "maxSeconds");
        DurationBudget budget;
        try {
            budget = DurationBudget.ofSeconds(minSeconds, maxSeconds);
        } catch (IllegalArgumentException e) {
            throw new ApiError(400, "durationBudget inválido: " + e.getMessage());
        }
        try {
            return new StoryBrief(topic, title, coreFacts, constraints, budget);
        } catch (RuntimeException e) {
            throw new ApiError(400, "StoryBrief inválido: " + e.getMessage());
        }
    }

    /**
     * Body opcional de POST /stories/{id}/generate: null (sin body o sin
     * campos) = todas las escenas aprobadas; { "sceneIds": [...] } = solo
     * esas. Una lista vacía llega como lista vacía (el servicio la rechaza
     * con 400, igual que reset-generation). Otro campo, o sceneIds que no es
     * una lista de textos, es 400.
     */
    static List<String> parseOptionalSceneIds(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        for (String key : body.keySet()) {
            if (!"sceneIds".equals(key)) {
                throw new ApiError(400, "Campo desconocido '" + key + "': el body de generate solo admite { \"sceneIds\": [...] }.");
            }
        }
        if (!(body.get("sceneIds") instanceof List<?> list)) {
            throw new ApiError(400, "'sceneIds' debe ser un array de ids de escena.");
        }
        List<String> ids = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new ApiError(400, "'sceneIds' debe contener solo ids de escena (texto no vacío), llegó: " + item);
            }
            ids.add(text);
        }
        return ids;
    }

    // --- helpers --------------------------------------------------------------------------

    private static Decision parseDecision(String text) {
        try {
            return Decision.valueOf(text.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiError(400, "'decision' debe ser APPROVE o REJECT, llegó: '" + text + "'.");
        }
    }

    private static GenerationTier parseTier(String text) {
        try {
            return GenerationTier.valueOf(text.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiError(400, "'tierOverride' debe ser STANDARD o PREMIUM, llegó: '" + text + "'.");
        }
    }

    private static int intField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) return number.intValue();
        throw new ApiError(400, "Falta o no es numérico el campo '" + key + "'.");
    }

    private static long longField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) return number.longValue();
        throw new ApiError(400, "Falta o no es numérico el campo '" + key + "'.");
    }

    private static String stringField(Map<String, Object> map, String key, boolean required) {
        Object value = map.get(key);
        if (value == null) {
            if (required) throw new ApiError(400, "Falta el campo requerido '" + key + "'.");
            return null;
        }
        return String.valueOf(value);
    }

    private static List<String> stringList(Map<String, Object> map, String key, boolean required) {
        Object value = map.get(key);
        if (!(value instanceof List<?> list)) {
            if (required) throw new ApiError(400, "Falta o no es un array el campo '" + key + "'.");
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) result.add(String.valueOf(item));
        return result;
    }
}
