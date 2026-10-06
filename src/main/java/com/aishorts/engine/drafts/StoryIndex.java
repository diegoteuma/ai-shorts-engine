package com.aishorts.engine.drafts;

import com.aishorts.engine.domain.GenerationStatus;
import com.aishorts.engine.domain.Scene;
import com.aishorts.engine.domain.SceneApprovalStatus;
import com.aishorts.engine.domain.SceneCostStatus;
import com.aishorts.engine.domain.Story;
import com.aishorts.engine.persistence.PersistenceException;
import com.aishorts.engine.persistence.StoryRepository;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vista de solo lectura de data/stories, archivo por archivo: a diferencia
 * de StoryRepository.findAll (que falla entera si un solo JSON está
 * corrupto), cada archivo se lee dentro de su propio try/catch. La usan
 * GET /stories (resumen con progreso) y el generador de borradores (para
 * no repetir temas ni ids).
 */
public final class StoryIndex {

    private final Path storiesDir;
    private final StoryRepository repository;

    public StoryIndex(Path storiesDir, StoryRepository repository) {
        this.storiesDir = storiesDir;
        this.repository = repository;
    }

    /** {id, title, topic, progress} por historia; un archivo ilegible aparece como {id, error}. */
    public List<Map<String, Object>> summaries() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String id : storyIds()) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("id", id);
            try {
                Story story = repository.findById(id)
                        .orElseThrow(() -> new PersistenceException("El archivo de '" + id + "' desapareció mientras se listaba."));
                summary.put("title", story.title());
                summary.put("topic", story.topic());
                summary.put("progress", progress(story));
            } catch (RuntimeException e) {
                summary.put("error", "No se pudo leer la historia: " + e.getMessage());
            }
            result.add(summary);
        }
        return result;
    }

    /** Historias legibles como ExistingStory (para existingStories y temas repetidos). */
    public List<ExistingStory> existingStories() {
        List<ExistingStory> result = new ArrayList<>();
        for (String id : storyIds()) {
            try {
                repository.findById(id).ifPresent(s -> result.add(new ExistingStory(s.id(), s.title(), s.topic())));
            } catch (RuntimeException e) {
                // corrupta: igual ocupa su id (ver storyIds), pero no aporta tema
            }
        }
        return result;
    }

    /** Ids de todos los archivos de historia, incluidos los corruptos (un id ocupado está ocupado aunque no se pueda leer). */
    public List<String> storyIds() {
        List<String> ids = new ArrayList<>();
        if (!Files.isDirectory(storiesDir)) {
            return ids;
        }
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(storiesDir, "*.json")) {
            for (Path file : dir) {
                String name = file.getFileName().toString();
                ids.add(name.substring(0, name.length() - ".json".length()));
            }
        } catch (IOException e) {
            throw new PersistenceException("No se pudo listar las historias en '" + storiesDir + "'.", e);
        }
        ids.sort(null);
        return ids;
    }

    public boolean storyExists(String id) {
        return storyIds().contains(id);
    }

    static Map<String, Object> progress(Story story) {
        List<Scene> scenes = story.scenes();
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("scenes", scenes.size());
        progress.put("promptsApproved", scenes.stream().filter(s -> s.promptStatus() == SceneApprovalStatus.APPROVED).count());
        progress.put("narrated", scenes.stream().filter(s -> s.narrationAudioPath() != null).count());
        progress.put("costsApproved", scenes.stream().filter(s -> s.costStatus() == SceneCostStatus.APPROVED).count());
        progress.put("generationCompleted", scenes.stream().filter(s -> s.generationStatus() == GenerationStatus.COMPLETED).count());
        progress.put("stage", story.status().name());
        return progress;
    }
}
