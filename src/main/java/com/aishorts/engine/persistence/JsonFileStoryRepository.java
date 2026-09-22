package com.aishorts.engine.persistence;

import com.aishorts.engine.domain.Story;
import com.aishorts.engine.domain.StorySnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Un archivo .json por historia (baseDir/{id}.json). Sin base de datos, a
 * propósito: no hay que instalar ni configurar nada, así que esto funciona
 * hoy mismo, en este sandbox y en tu máquina, sin agregar infraestructura
 * (ver la charla sobre GCP: para el volumen de un piloto — una historia a
 * la vez, unas pocas por semana — un archivo por historia alcanza y sobra,
 * y no hay ningún costo de nube involucrado).
 *
 * El día que el volumen lo justifique, {@link StoryRepository} es la
 * interfaz que aísla ese cambio: esta es la única clase que habría que
 * reemplazar por una implementación con SQLite/Postgres.
 *
 * Cada guardado escribe a un archivo temporal y lo mueve de forma atómica
 * al nombre final, para que un corte a mitad de escritura (el proceso
 * muere, se corta la luz) nunca deje un .json a medio escribir/corrupto.
 */
public final class JsonFileStoryRepository implements StoryRepository {

    private final Path baseDir;
    private final ObjectMapper objectMapper;

    public JsonFileStoryRepository(Path baseDir, ObjectMapper objectMapper) {
        this.baseDir = baseDir;
        this.objectMapper = objectMapper;
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new PersistenceException("No se pudo crear el directorio de datos '" + baseDir + "'.", e);
        }
    }

    @Override
    public void save(Story story) {
        Path file = fileFor(story.id());
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        try {
            String json = objectMapper.writeValueAsString(SnapshotMapper.toMap(story.toSnapshot()));
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new PersistenceException("No se pudo guardar la historia '" + story.id() + "' en '" + file + "'.", e);
        }
    }

    @Override
    public Optional<Story> findById(String storyId) {
        Path file = fileFor(storyId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(read(file));
    }

    @Override
    public List<Story> findAll() {
        List<Story> stories = new ArrayList<>();
        if (!Files.isDirectory(baseDir)) {
            return stories;
        }
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(baseDir, "*.json")) {
            for (Path file : dir) {
                stories.add(read(file));
            }
        } catch (IOException e) {
            throw new PersistenceException("No se pudo listar las historias en '" + baseDir + "'.", e);
        }
        return stories;
    }

    @SuppressWarnings("unchecked")
    private Story read(Path file) {
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Object> map = (Map<String, Object>) objectMapper.readValue(json, Object.class);
            StorySnapshot snapshot = SnapshotMapper.toStorySnapshot(map);
            return Story.fromSnapshot(snapshot);
        } catch (IOException e) {
            throw new PersistenceException("No se pudo leer '" + file + "'.", e);
        }
    }

    private Path fileFor(String storyId) {
        String safeName = storyId.replaceAll("[^a-zA-Z0-9_-]", "_");
        return baseDir.resolve(safeName + ".json");
    }
}
