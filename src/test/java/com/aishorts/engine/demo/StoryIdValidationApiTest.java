package com.aishorts.engine.demo;

import com.aishorts.engine.duration.WordsPerSecondDurationEstimator;
import com.aishorts.engine.higgsfield.HiggsfieldClient;
import com.aishorts.engine.script.ScriptDraftingService;
import com.aishorts.engine.tts.TtsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El id de historia que manda el cliente en POST /stories termina en nombres
 * de archivo (la historia, y los audios vía los ids de escena
 * {idHistoria}-{rol}). Antes se aceptaba cualquiera: "../escape" creaba la
 * historia (con el alias saneado "___escape") y la narración escribía
 * audioDir/../escape-gancho.mp3; "a.b" y "a_b" compartían archivo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StoryIdValidationApiTest.FakeServicesConfig.class)
class StoryIdValidationApiTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void engineProperties(DynamicPropertyRegistry registry) {
        registry.add("DATA_DIR", () -> dataDir.resolve("stories").toString());
        registry.add("AUDIO_DIR", () -> dataDir.resolve("audio").toString());
        registry.add("CLIPS_DIR", () -> dataDir.resolve("clips").toString());
        registry.add("DRAFTS_DIR", () -> dataDir.resolve("drafts").toString());
        registry.add("CLAUDE_API_KEY", () -> "test-claude-key");
        registry.add("CLAUDE_MODEL", () -> "test-claude-model");
        registry.add("HIGGSFIELD_BASE_URL", () -> "https://higgsfield.invalid");
        registry.add("HIGGSFIELD_API_KEY_ID", () -> "test-id");
        registry.add("HIGGSFIELD_API_KEY_SECRET", () -> "test-secret");
        registry.add("HIGGSFIELD_MODEL_STANDARD",
                () -> com.aishorts.engine.higgsfield.KnownHiggsfieldPricing.STANDARD_MODEL_ID);
        registry.add("HIGGSFIELD_MODEL_PREMIUM",
                () -> com.aishorts.engine.higgsfield.KnownHiggsfieldPricing.PREMIUM_MODEL_ID);
        registry.add("ELEVENLABS_API_KEY", () -> "test-elevenlabs-key");
        registry.add("ELEVENLABS_VOICE_ID", () -> "test-voice");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void aPathTraversalStoryIdIsRejectedAndNothingIsWrittenOutsideTheConfiguredFolders() throws IOException {
        ResponseEntity<Map> created = rest.postForEntity(url("/stories"), createBody("../escape", "Escape"), Map.class);

        // Lo que podía hacer un cliente si la creación pasaba: operar la historia por su alias saneado
        // y llegar a la narración (que escribe audioDir.resolve(idEscena + ".mp3")).
        String alias = "___escape";
        Map<String, Object> story = getBody("/stories/" + alias);
        if (story != null) {
            List<String> sceneIds = sceneIds(story);
            rest.postForEntity(url("/stories/" + alias + "/tiers"), tiersBody(sceneIds), Map.class);
            rest.postForEntity(url("/stories/" + alias + "/prompt-decisions"), approveAll(sceneIds), Map.class);
            rest.postForEntity(url("/stories/" + alias + "/narration"), null, Map.class);
        }

        assertThat(filesOutsideConfiguredFolders()).as("archivos escritos fuera de stories/audio/clips/drafts").isEmpty();
        assertThat(created.getStatusCode().value()).as("POST /stories con id '../escape': %s", created.getBody()).isEqualTo(400);
        assertThat(String.valueOf(created.getBody().get("error"))).contains("../escape").contains("A-Za-z0-9_-");
    }

    @Test
    @SuppressWarnings("unchecked")
    void idsThatUsedToMapToTheSameFileNoLongerCollide() {
        ResponseEntity<Map> first = rest.postForEntity(url("/stories"), createBody("a_b", "Primera"), Map.class);
        assertThat(first.getStatusCode().value()).isEqualTo(201);

        ResponseEntity<Map> second = rest.postForEntity(url("/stories"), createBody("a.b", "Segunda"), Map.class);

        assertThat(second.getStatusCode().value()).as("POST /stories con id 'a.b': %s", second.getBody()).isEqualTo(400);
        Map<String, Object> kept = getBody("/stories/a_b");
        assertThat(kept.get("id")).isEqualTo("a_b");
        assertThat(kept.get("title")).isEqualTo("Primera");
        // "a.b" ya no es un alias de "a_b"
        assertThat(rest.getForEntity(url("/stories/a.b"), Map.class).getStatusCode().value()).isNotEqualTo(200);
    }

    @Test
    void theStoryIdLengthIsCapped() {
        String atLimit = "x".repeat(64);
        assertThat(rest.postForEntity(url("/stories"), createBody(atLimit, "Al límite"), Map.class).getStatusCode().value()).isEqualTo(201);

        ResponseEntity<Map> tooLong = rest.postForEntity(url("/stories"), createBody("y".repeat(65), "Largo"), Map.class);
        assertThat(tooLong.getStatusCode().value()).isEqualTo(400);
        assertThat(String.valueOf(tooLong.getBody().get("error"))).contains("64");
    }

    @Test
    void withoutStoryIdTheGeneratedUuidIsStillAccepted() {
        Map<String, Object> body = createBody("ignorado", "Sin id");
        body.remove("storyId");
        ResponseEntity<Map> created = rest.postForEntity(url("/stories"), body, Map.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(String.valueOf(created.getBody().get("id"))).matches("[0-9a-f-]{36}");
    }

    // --- helpers ----------------------------------------------------------------------------

    private Map<String, Object> createBody(String storyId, String title) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("storyId", storyId);
        body.put("topic", "Explosión de Tunguska (1908)");
        body.put("title", title);
        body.put("coreFacts", List.of("El 30 de junio de 1908 un objeto espacial explotó en la atmósfera sobre Siberia."));
        body.put("durationBudget", Map.of("minSeconds", 20, "maxSeconds", 60));
        return body;
    }

    private Map<String, Object> tiersBody(List<String> sceneIds) {
        Map<String, Object> tiers = new LinkedHashMap<>();
        for (String id : sceneIds) {
            tiers.put(id, Map.of("movementComplexity", 5, "elementCount", 5, "physicalRealism", 5, "cameraDynamism", 5));
        }
        return tiers;
    }

    private List<Map<String, Object>> approveAll(List<String> sceneIds) {
        return sceneIds.stream().map(id -> Map.<String, Object>of("sceneId", id, "decision", "APPROVE")).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> sceneIds(Map<String, Object> story) {
        return ((List<Map<String, Object>>) story.get("scenes")).stream().map(s -> (String) s.get("id")).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getBody(String path) {
        ResponseEntity<Map> response = rest.getForEntity(url(path), Map.class);
        return response.getStatusCode().value() == 200 ? (Map<String, Object>) response.getBody() : null;
    }

    private List<Path> filesOutsideConfiguredFolders() throws IOException {
        List<Path> allowed = Stream.of("stories", "audio", "clips", "drafts").map(dataDir::resolve).toList();
        try (Stream<Path> files = Files.walk(dataDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(f -> allowed.stream().noneMatch(f::startsWith))
                    .toList();
        }
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @TestConfiguration
    static class FakeServicesConfig {

        @Bean
        @Primary
        HiggsfieldClient fakeHiggsfieldClient() {
            return new FakeHiggsfieldClient();
        }

        @Bean
        @Primary
        TtsService fakeTtsService() {
            return new FakeTtsService(WordsPerSecondDurationEstimator.neutralSpanish());
        }

        @Bean
        @Primary
        ScriptDraftingService fakeScriptDraftingService() {
            return new FakeScriptDraftingService();
        }
    }
}
