package com.aishorts.engine.persistence;

import com.aishorts.engine.domain.Scene;
import com.aishorts.engine.domain.SceneRole;
import com.aishorts.engine.domain.Story;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Un archivo por historia, con el id tal cual: un id que no sirve de nombre de archivo se rechaza, no se "arregla". */
class JsonFileStoryRepositoryTest {

    @TempDir
    Path tmp;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void twoIdsThatOnlyDifferInAnInvalidCharacterDoNotOverwriteEachOther() {
        JsonFileStoryRepository repo = new JsonFileStoryRepository(tmp.resolve("stories"), mapper);
        repo.save(story("a_b", "Primera"));

        assertThatThrownBy(() -> repo.save(story("a.b", "Segunda")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a.b");

        assertThat(repo.findById("a_b")).get().extracting(Story::title).isEqualTo("Primera");
        assertThatThrownBy(() -> repo.findById("a.b")).isInstanceOf(IllegalArgumentException.class);
        assertThat(repo.findAll()).extracting(Story::id).containsExactly("a_b");
    }

    @Test
    void aTraversalIdIsRejectedAndNothingIsWritten() throws IOException {
        Path base = tmp.resolve("stories");
        JsonFileStoryRepository repo = new JsonFileStoryRepository(base, mapper);

        assertThatThrownBy(() -> repo.save(story("../fuera", "x"))).isInstanceOf(IllegalArgumentException.class);

        try (var files = Files.walk(tmp)) {
            assertThat(files.filter(Files::isRegularFile).toList()).isEmpty();
        }
    }

    @Test
    void validIdsIncludingTheGeneratedUuidRoundTrip() {
        JsonFileStoryRepository repo = new JsonFileStoryRepository(tmp.resolve("stories"), mapper);
        String uuid = UUID.randomUUID().toString();
        for (String id : List.of("tunguska", "tambora-mundo-interconectado", "A_b-9", uuid, "x".repeat(64))) {
            repo.save(story(id, "t-" + id));
            assertThat(repo.findById(id)).get().extracting(Story::title).isEqualTo("t-" + id);
        }
        assertThatThrownBy(() -> repo.save(story("x".repeat(65), "largo"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFileSavedBeforeValidationWithAnotherIdInsideFailsLoudly() throws IOException {
        // Lo que dejaba el repositorio viejo al guardar "a.b": el archivo a_b.json con id "a.b" adentro.
        Path base = tmp.resolve("stories");
        Files.createDirectories(base);
        Files.writeString(base.resolve("a_b.json"),
                mapper.writeValueAsString(SnapshotMapper.toMap(story("a.b", "vieja").toSnapshot())));
        JsonFileStoryRepository repo = new JsonFileStoryRepository(base, mapper);

        assertThatThrownBy(() -> repo.findById("a_b"))
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("contiene la historia 'a.b', no 'a_b'");
    }

    @Test
    void theTrackedTunguskaStoryStillLoads() throws IOException {
        Path base = tmp.resolve("stories");
        Files.createDirectories(base);
        Files.copy(Path.of("data", "stories", "tunguska.json"), base.resolve("tunguska.json"));
        JsonFileStoryRepository repo = new JsonFileStoryRepository(base, mapper);

        Story story = repo.findById("tunguska").orElseThrow();

        assertThat(story.id()).isEqualTo("tunguska");
        assertThat(story.scenes()).hasSize(6);
        assertThat(repo.findAll()).extracting(Story::id).containsExactly("tunguska");
    }

    private static Story story(String id, String title) {
        Scene scene = new Scene(id + "-gancho", SceneRole.GANCHO, 1, "narración", "prompt", Duration.ofSeconds(4));
        return new Story(id, "tema", title, List.of(scene));
    }
}
