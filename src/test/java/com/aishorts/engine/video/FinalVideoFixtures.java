package com.aishorts.engine.video;

import com.aishorts.engine.clips.Mp4Fixtures;
import com.aishorts.engine.domain.Story;
import com.aishorts.engine.persistence.SnapshotMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Historias en el formato de SnapshotMapper, listas (o casi) para el video final. */
final class FinalVideoFixtures {

    static final List<String> ROLES = List.of("GANCHO", "EXPLICACION", "CONTEXTO", "GIRO", "CONSECUENCIA", "CIERRE");

    private FinalVideoFixtures() {
    }

    /** Una escena COMPLETED con su clip local registrado como {id}.mp4. narrationAudioPath es de "otra máquina" a propósito. */
    static Map<String, Object> scene(String id, String role, int order, String narrationText, String targetDuration) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("role", role);
        m.put("order", order);
        m.put("narrationText", narrationText);
        m.put("visualPrompt", "Hyperrealistic cinematic documentary footage, vertical 9:16 composition");
        m.put("targetDuration", targetDuration);
        m.put("tierRecommendation", null);
        m.put("chosenTier", "STANDARD");
        m.put("chosenModelId", "modelo-falso");
        m.put("promptStatus", "APPROVED");
        m.put("promptRejectionNote", null);
        m.put("costStatus", "APPROVED");
        m.put("costEstimate", null);
        m.put("costRejectionNote", null);
        m.put("narrationAudioPath", "/home/otra-maquina/data/audio/" + id + ".mp3");
        m.put("generationStatus", "COMPLETED");
        m.put("higgsfieldRequestId", "req-" + id);
        m.put("higgsfieldStatusUrl", "https://higgsfield.invalid/status/" + id);
        m.put("generatedAssetUrl", "https://higgsfield.invalid/clips/" + id + ".mp4");
        m.put("generationFailureReason", null);
        m.put("localClipPath", id + ".mp4");
        m.put("localClipFailureReason", null);
        return m;
    }

    /** 6 escenas {storyId}-{rol} listas: COMPLETED, clip 720x1280 válido y audio en disco. */
    static List<Map<String, Object>> readyScenes(String storyId, Path clipsDir, Path audioDir) {
        List<Map<String, Object>> scenes = new ArrayList<>();
        for (int i = 0; i < ROLES.size(); i++) {
            String id = storyId + "-" + ROLES.get(i).toLowerCase();
            scenes.add(scene(id, ROLES.get(i), i + 1, "Narración de la escena " + (i + 1) + ".", "PT4.458S"));
            writeMedia(id, clipsDir, audioDir);
        }
        return scenes;
    }

    static void writeMedia(String sceneId, Path clipsDir, Path audioDir) {
        try {
            Files.createDirectories(clipsDir);
            Files.createDirectories(audioDir);
            Files.write(clipsDir.resolve(sceneId + ".mp4"), Mp4Fixtures.valid());
            Files.writeString(audioDir.resolve(sceneId + ".mp3"), "mp3 simulado " + sceneId);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Story story(String storyId, List<Map<String, Object>> scenes) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", storyId);
        map.put("topic", "Explosión de Tunguska (1908)");
        map.put("title", "La explosión que derribó 80 millones de árboles");
        map.put("scenes", scenes);
        return Story.fromSnapshot(SnapshotMapper.toStorySnapshot(map));
    }
}
