package com.aishorts.engine.drafts;

import com.aishorts.engine.persistence.PersistenceException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Un archivo .json por borrador (baseDir/{id}.json), modelado sobre
 * JsonFileStoryRepository: escribe a un temporal y lo mueve al nombre
 * final, para que un corte nunca deje un archivo a medio escribir.
 *
 * Diferencia clave: {@link #create} NUNCA sobrescribe (el move final se
 * hace sin REPLACE_EXISTING y falla con 409 si el archivo ya existe);
 * solo {@link #update} reemplaza, y solo un borrador que ya existe.
 * Todos los ids se validan contra DraftText.ID_PATTERN antes de tocar el
 * disco, así un id con "../" nunca arma una ruta fuera de baseDir.
 */
public final class JsonFileDraftRepository {

    private final Path baseDir;
    private final ObjectMapper objectMapper;

    /** El directorio se crea recién al guardar el primer borrador, no al arrancar. */
    public JsonFileDraftRepository(Path baseDir, ObjectMapper objectMapper) {
        this.baseDir = baseDir;
        this.objectMapper = objectMapper;
    }

    public void create(StoryDraft draft) {
        Path file = fileFor(draft.id);
        Path tmp = tempFileFor(draft.id);
        try {
            Files.createDirectories(baseDir);
            Files.writeString(tmp, toJson(draft), StandardCharsets.UTF_8);
            Files.move(tmp, file);
        } catch (FileAlreadyExistsException e) {
            throw new DraftException(409, "Ya existe un borrador con id '" + draft.id + "'; no se sobrescribe.");
        } catch (IOException e) {
            throw new PersistenceException("No se pudo guardar el borrador '" + draft.id + "' en '" + file + "'.", e);
        } finally {
            deleteQuietly(tmp);
        }
    }

    public void update(StoryDraft draft) {
        Path file = fileFor(draft.id);
        if (!Files.isRegularFile(file)) {
            throw new PersistenceException("No existe el borrador '" + draft.id + "' para actualizar.");
        }
        Path tmp = tempFileFor(draft.id);
        try {
            Files.writeString(tmp, toJson(draft), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new PersistenceException("No se pudo actualizar el borrador '" + draft.id + "' en '" + file + "'.", e);
        } finally {
            deleteQuietly(tmp);
        }
    }

    public Optional<StoryDraft> findById(String draftId) {
        Path file = fileFor(draftId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(read(file));
    }

    public boolean exists(String draftId) {
        return DraftText.isValidId(draftId) && Files.isRegularFile(baseDir.resolve(draftId + ".json"));
    }

    /** Ids de todos los archivos de borrador, incluidos los que no se pueden leer (cuentan como ocupados). */
    public List<String> listIds() {
        List<String> ids = new ArrayList<>();
        for (Path file : jsonFiles()) {
            String name = file.getFileName().toString();
            ids.add(name.substring(0, name.length() - ".json".length()));
        }
        return ids;
    }

    /** Todos los borradores legibles; los corruptos se saltean (ver {@link #summaries} para verlos). */
    public List<StoryDraft> findAllReadable() {
        List<StoryDraft> drafts = new ArrayList<>();
        for (Path file : jsonFiles()) {
            try {
                drafts.add(read(file));
            } catch (RuntimeException e) {
                // un archivo corrupto no debe impedir listar el resto
            }
        }
        return drafts;
    }

    /** Resumen por borrador para GET /story-drafts; un archivo ilegible aparece como {id, error}. */
    public List<Map<String, Object>> summaries() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Path file : jsonFiles()) {
            String name = file.getFileName().toString();
            String id = name.substring(0, name.length() - ".json".length());
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("id", id);
            try {
                StoryDraft draft = read(file);
                summary.put("title", draft.title);
                summary.put("topic", draft.topic);
                summary.put("status", draft.status);
                long hard = draft.violations == null ? 0 : draft.violations.stream().filter(Violation::isHard).count();
                long soft = draft.violations == null ? 0 : draft.violations.size() - hard;
                summary.put("hardViolations", hard);
                summary.put("softViolations", soft);
                summary.put("createdAt", draft.createdAt);
                summary.put("approvedStoryId", draft.approvedStoryId);
            } catch (RuntimeException e) {
                summary.put("error", "No se pudo leer el borrador: " + e.getMessage());
            }
            result.add(summary);
        }
        return result;
    }

    private List<Path> jsonFiles() {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(baseDir)) {
            return files;
        }
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(baseDir, "*.json")) {
            for (Path file : dir) {
                files.add(file);
            }
        } catch (IOException e) {
            throw new PersistenceException("No se pudo listar los borradores en '" + baseDir + "'.", e);
        }
        files.sort(null);
        return files;
    }

    private StoryDraft read(Path file) {
        try {
            return objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8), StoryDraft.class);
        } catch (IOException e) {
            throw new PersistenceException("No se pudo leer '" + file + "'.", e);
        }
    }

    private String toJson(StoryDraft draft) throws IOException {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(draft);
    }

    private Path fileFor(String draftId) {
        if (!DraftText.isValidId(draftId)) {
            throw new IllegalArgumentException("Id de borrador inválido: '" + draftId + "'.");
        }
        return baseDir.resolve(draftId + ".json");
    }

    private Path tempFileFor(String draftId) {
        fileFor(draftId);
        return baseDir.resolve(draftId + ".json." + UUID.randomUUID() + ".tmp");
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // el temporal huérfano no rompe nada: no termina en .json
        }
    }
}
