package com.aishorts.engine.drafts;

import com.aishorts.engine.difficulty.TierRecommendation;
import com.aishorts.engine.domain.GenerationTier;
import com.aishorts.engine.domain.Scene;
import com.aishorts.engine.domain.SceneRole;
import com.aishorts.engine.domain.Story;
import com.aishorts.engine.persistence.JsonFileStoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** GET /stories: resumen {id, title, topic, progress} por historia, tolerante a archivos corruptos. */
@SuppressWarnings("unchecked")
class StoryListApiTest extends DraftApiTestSupport {

    @Test
    void listStories_returnsSummaryWithProgress_andACorruptFileDoesNotBreakTheList() throws Exception {
        Story story = buildStory("tunguska", "Explosión de Tunguska (1908)");
        for (Scene scene : story.scenes().subList(0, 2)) {
            scene.proposeTier(new TierRecommendation(GenerationTier.STANDARD, 10, "test"));
            scene.approvePrompt();
        }
        new JsonFileStoryRepository(storiesDir(), new ObjectMapper()).save(story);
        Files.writeString(storiesDir().resolve("rota.json"), "{\"id\": \"rota\", \"scenes\": [", StandardCharsets.UTF_8);

        List<Map<String, Object>> list = getList("/stories").getBody();

        assertThat(list).hasSize(2);
        Map<String, Object> rota = list.get(0);
        assertThat(rota.get("id")).isEqualTo("rota");
        assertThat((String) rota.get("error")).isNotBlank();
        Map<String, Object> tunguska = list.get(1);
        assertThat(tunguska.keySet()).containsExactly("id", "title", "topic", "progress");
        assertThat(tunguska.get("title")).isEqualTo("La explosión de 1908");
        assertThat(tunguska.get("topic")).isEqualTo("Explosión de Tunguska (1908)");
        assertThat(tunguska.get("progress")).isEqualTo(Map.of(
                "scenes", 6, "promptsApproved", 2, "narrated", 0, "costsApproved", 0,
                "generationCompleted", 0, "stage", "PROMPTS_PENDING_REVIEW"));
    }

    @Test
    void listStories_emptyDirectory_isEmptyList() {
        assertThat(getList("/stories").getBody()).isEmpty();
    }

    /** Escribe una historia mínima (6 escenas en DRAFT) en dir, como lo haría el repositorio real. */
    static void writeStoryFile(Path dir, String id, String topic) {
        new JsonFileStoryRepository(dir, new ObjectMapper()).save(buildStory(id, topic));
    }

    static Story buildStory(String id, String topic) {
        List<Scene> scenes = new ArrayList<>();
        int order = 1;
        for (SceneRole role : SceneRole.values()) {
            scenes.add(new Scene(id + "-" + role.name().toLowerCase(), role, order++, "Narración " + role,
                    "Prompt " + role, Duration.ofSeconds(6)));
        }
        return new Story(id, topic, "La explosión de 1908", scenes);
    }
}
