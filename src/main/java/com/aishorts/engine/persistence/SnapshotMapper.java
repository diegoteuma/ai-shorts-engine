package com.aishorts.engine.persistence;

import com.aishorts.engine.difficulty.TierRecommendation;
import com.aishorts.engine.domain.GenerationStatus;
import com.aishorts.engine.domain.GenerationTier;
import com.aishorts.engine.domain.SceneApprovalStatus;
import com.aishorts.engine.domain.SceneCostStatus;
import com.aishorts.engine.domain.SceneRole;
import com.aishorts.engine.domain.SceneSnapshot;
import com.aishorts.engine.domain.StorySnapshot;
import com.aishorts.engine.higgsfield.EstimateResponse;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Convierte StorySnapshot/SceneSnapshot a/desde la representación que
 * entiende {@link com.aishorts.engine.json.Json} (Map/List/String/Number/
 * Boolean/null) — ese escritor/lector no sabe de Duration, enums ni
 * records, así que ese "aplanado" vive acá, aislado del resto del dominio.
 *
 * Público (no solo para persistencia): la API REST reusa exactamente este
 * mismo mapeo para serializar el estado de una Story en sus respuestas, en
 * vez de tener un segundo formato JSON paralelo.
 */
public final class SnapshotMapper {

    private SnapshotMapper() {
    }

    public static Map<String, Object> toMap(StorySnapshot story) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", story.id());
        map.put("topic", story.topic());
        map.put("title", story.title());
        List<Object> scenes = new ArrayList<>();
        for (SceneSnapshot scene : story.scenes()) {
            scenes.add(toMap(scene));
        }
        map.put("scenes", scenes);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static StorySnapshot toStorySnapshot(Map<String, Object> map) {
        String id = str(map, "id");
        String topic = str(map, "topic");
        String title = str(map, "title");
        List<SceneSnapshot> scenes = new ArrayList<>();
        for (Object raw : (List<Object>) map.getOrDefault("scenes", List.of())) {
            scenes.add(toSceneSnapshot((Map<String, Object>) raw));
        }
        return new StorySnapshot(id, topic, title, scenes);
    }

    private static Map<String, Object> toMap(SceneSnapshot scene) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", scene.id());
        map.put("role", scene.role().name());
        map.put("order", scene.order());
        map.put("narrationText", scene.narrationText());
        map.put("visualPrompt", scene.visualPrompt());
        map.put("targetDuration", scene.targetDuration().toString());
        map.put("tierRecommendation", toMap(scene.tierRecommendation()));
        map.put("chosenTier", scene.chosenTier() != null ? scene.chosenTier().name() : null);
        map.put("chosenModelId", scene.chosenModelId());
        map.put("promptStatus", scene.promptStatus().name());
        map.put("promptRejectionNote", scene.promptRejectionNote());
        map.put("costStatus", scene.costStatus().name());
        map.put("costEstimate", toMap(scene.costEstimate()));
        map.put("costRejectionNote", scene.costRejectionNote());
        map.put("narrationAudioPath", scene.narrationAudioPath());
        map.put("generationStatus", scene.generationStatus().name());
        map.put("higgsfieldRequestId", scene.higgsfieldRequestId());
        map.put("higgsfieldStatusUrl", scene.higgsfieldStatusUrl());
        map.put("generatedAssetUrl", scene.generatedAssetUrl());
        map.put("generationFailureReason", scene.generationFailureReason());
        return map;
    }

    @SuppressWarnings("unchecked")
    private static SceneSnapshot toSceneSnapshot(Map<String, Object> map) {
        return new SceneSnapshot(
                str(map, "id"),
                SceneRole.valueOf(str(map, "role")),
                intVal(map, "order"),
                str(map, "narrationText"),
                str(map, "visualPrompt"),
                Duration.parse(str(map, "targetDuration")),
                toTierRecommendation((Map<String, Object>) map.get("tierRecommendation")),
                enumOrNull(GenerationTier.class, str(map, "chosenTier")),
                str(map, "chosenModelId"),
                SceneApprovalStatus.valueOf(str(map, "promptStatus")),
                str(map, "promptRejectionNote"),
                SceneCostStatus.valueOf(str(map, "costStatus")),
                toEstimateResponse((Map<String, Object>) map.get("costEstimate")),
                str(map, "costRejectionNote"),
                str(map, "narrationAudioPath"),
                GenerationStatus.valueOf(str(map, "generationStatus")),
                str(map, "higgsfieldRequestId"),
                str(map, "higgsfieldStatusUrl"),
                str(map, "generatedAssetUrl"),
                str(map, "generationFailureReason")
        );
    }

    private static Map<String, Object> toMap(TierRecommendation rec) {
        if (rec == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("tier", rec.tier().name());
        map.put("score", rec.score());
        map.put("reason", rec.reason());
        return map;
    }

    private static TierRecommendation toTierRecommendation(Map<String, Object> map) {
        if (map == null) return null;
        return new TierRecommendation(GenerationTier.valueOf(str(map, "tier")), intVal(map, "score"), str(map, "reason"));
    }

    private static Map<String, Object> toMap(EstimateResponse estimate) {
        if (estimate == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("cost", estimate.cost());
        map.put("currency", estimate.currency());
        map.put("modelId", estimate.modelId());
        map.put("raw", estimate.raw());
        return map;
    }

    @SuppressWarnings("unchecked")
    private static EstimateResponse toEstimateResponse(Map<String, Object> map) {
        if (map == null) return null;
        BigDecimal cost = toBigDecimal(map.get("cost"));
        String currency = str(map, "currency");
        String modelId = str(map, "modelId");
        Map<String, Object> raw = (Map<String, Object>) map.getOrDefault("raw", Map.of());
        return new EstimateResponse(cost, currency, modelId, raw);
    }

    // --- helpers de lectura tolerantes a null ----------------------------------------------

    private static String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static int intVal(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) return number.intValue();
        throw new PersistenceException("Falta o no es numérico el campo '" + key + "' en el JSON guardado.");
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return new BigDecimal(number.toString());
        throw new PersistenceException("Se esperaba un número para 'cost', llegó: " + value);
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String name) {
        return name == null ? null : Enum.valueOf(type, name);
    }
}
