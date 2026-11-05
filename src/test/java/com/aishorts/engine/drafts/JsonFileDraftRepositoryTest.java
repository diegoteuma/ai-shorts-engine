package com.aishorts.engine.drafts;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonFileDraftRepositoryTest {

    @TempDir
    Path dir;

    private StoryDraft draft(String id, String title) {
        StoryDraft draft = new StoryDraft();
        draft.id = id;
        draft.title = title;
        draft.status = DraftStatus.PENDING_REVIEW;
        return draft;
    }

    @Test
    void create_neverOverwritesAnExistingDraft() throws Exception {
        JsonFileDraftRepository repository = new JsonFileDraftRepository(dir.resolve("drafts"), new ObjectMapper());
        repository.create(draft("tunguska", "primero"));
        byte[] before = Files.readAllBytes(dir.resolve("drafts/tunguska.json"));

        assertThatThrownBy(() -> repository.create(draft("tunguska", "segundo")))
                .isInstanceOfSatisfying(DraftException.class, e -> assertThat(e.status()).isEqualTo(409));

        assertThat(Files.readAllBytes(dir.resolve("drafts/tunguska.json"))).isEqualTo(before);
        assertThat(repository.findById("tunguska").orElseThrow().title).isEqualTo("primero");
        try (var files = Files.list(dir.resolve("drafts"))) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("tunguska.json"); // sin temporales huérfanos
        }
    }

    @Test
    void update_replacesOnlyAnExistingDraft() {
        JsonFileDraftRepository repository = new JsonFileDraftRepository(dir, new ObjectMapper());
        assertThatThrownBy(() -> repository.update(draft("nuevo", "x"))).isInstanceOf(RuntimeException.class);
        repository.create(draft("nuevo", "x"));
        StoryDraft changed = draft("nuevo", "y");
        repository.update(changed);
        assertThat(repository.findById("nuevo").orElseThrow().title).isEqualTo("y");
    }

    @Test
    void idsOutsideThePattern_neverReachTheFileSystem() {
        JsonFileDraftRepository repository = new JsonFileDraftRepository(dir, new ObjectMapper());
        for (String badId : new String[]{"../stories/tunguska", "..", "a/b", "A", "a_b", ""}) {
            assertThatThrownBy(() -> repository.findById(badId)).as(badId).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> repository.create(draft(badId, "x"))).as(badId).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(repository.exists("../x")).isFalse();
    }

    @Test
    void aDraftSavedBeforeCacheFieldsAndCost_stillLoads_withZerosAndNullCost() throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("viejo.json"), """
                {"id":"viejo","title":"t","status":"PENDING_REVIEW",
                 "usage":{"inputTokens":331207,"outputTokens":58441,"webSearchRequests":10}}""");
        StoryDraft loaded = new JsonFileDraftRepository(dir, new ObjectMapper()).findById("viejo").orElseThrow();
        assertThat(loaded.usage).isEqualTo(new DraftUsage(331207, 58441, 10, 0, 0));
        assertThat(loaded.estimatedCostUsd).isNull();
    }

    @Test
    void usageWithCacheFieldsAndCost_roundTrip() {
        JsonFileDraftRepository repository = new JsonFileDraftRepository(dir, new ObjectMapper());
        StoryDraft saved = draft("con-cache", "x");
        saved.usage = new DraftUsage(1000, 200, 3, 50_000, 400_000);
        saved.estimatedCostUsd = 0.2345;
        repository.create(saved);
        StoryDraft loaded = repository.findById("con-cache").orElseThrow();
        assertThat(loaded.usage).isEqualTo(saved.usage);
        assertThat(loaded.estimatedCostUsd).isEqualTo(0.2345);
    }

    @Test
    void constructor_doesNotCreateTheDirectory_untilTheFirstSave() {
        new JsonFileDraftRepository(dir.resolve("lazy"), new ObjectMapper());
        assertThat(dir.resolve("lazy")).doesNotExist();
    }
}
