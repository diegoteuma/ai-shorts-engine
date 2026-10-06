package com.aishorts.engine.drafts;

import com.aishorts.engine.higgsfield.HiggsfieldClient;
import com.aishorts.engine.higgsfield.KnownHiggsfieldPricing;
import com.aishorts.engine.script.ScriptDraftingService;
import com.aishorts.engine.tts.TtsService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Base de los tests HTTP del generador. Cero red:
 * - StoryDraftGenerator -> FakeStoryDraftGenerator (@Primary).
 * - HiggsfieldClient, TtsService (ElevenLabs) y ScriptDraftingService (el
 *   guionado con Claude de POST /stories) son @MockBean, para poder
 *   verificar que el flujo nuevo NUNCA los toca.
 * - Reloj fijo en 2026-10-06 para las fechas de consulta.
 * Los directorios de historias y borradores viven en un @TempDir y se
 * vacían antes de cada test.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(DraftApiTestSupport.FakeGeneratorConfig.class)
abstract class DraftApiTestSupport {

    /**
     * Un único directorio para TODAS las subclases: Spring cachea un solo
     * contexto para las tres (misma configuración), así que un @TempDir por
     * clase dejaría al contexto compartido apuntando al directorio ya
     * borrado de la primera.
     */
    static final Path dataDir = createDataDir();

    private static Path createDataDir() {
        try {
            Path dir = Files.createTempDirectory("story-drafts-api-test");
            dir.toFile().deleteOnExit();
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void engineProperties(DynamicPropertyRegistry registry) {
        registry.add("DATA_DIR", () -> dataDir.resolve("stories").toString());
        registry.add("AUDIO_DIR", () -> dataDir.resolve("audio").toString());
        registry.add("DRAFTS_DIR", () -> dataDir.resolve("drafts").toString());
        registry.add("CLAUDE_API_KEY", () -> "test-claude-key");
        registry.add("CLAUDE_MODEL", () -> "test-claude-model");
        registry.add("HIGGSFIELD_BASE_URL", () -> "https://higgsfield.invalid");
        registry.add("HIGGSFIELD_API_KEY_ID", () -> "test-id");
        registry.add("HIGGSFIELD_API_KEY_SECRET", () -> "test-secret");
        registry.add("HIGGSFIELD_MODEL_STANDARD", () -> KnownHiggsfieldPricing.STANDARD_MODEL_ID);
        registry.add("HIGGSFIELD_MODEL_PREMIUM", () -> KnownHiggsfieldPricing.PREMIUM_MODEL_ID);
        registry.add("ELEVENLABS_API_KEY", () -> "test-elevenlabs-key");
    }

    @MockBean
    HiggsfieldClient higgsfieldClient;

    @MockBean
    TtsService ttsService;

    @MockBean
    ScriptDraftingService scriptDraftingService;

    @Autowired
    FakeStoryDraftGenerator fake;

    @Autowired
    TestRestTemplate rest;

    @LocalServerPort
    int port;

    @BeforeEach
    void cleanState() throws IOException {
        fake.reset();
        for (String dir : List.of("stories", "drafts")) {
            Path path = dataDir.resolve(dir);
            Files.createDirectories(path);
            try (Stream<Path> files = Files.list(path)) {
                files.forEach(file -> {
                    try {
                        Files.delete(file);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        }
    }

    static Path storiesDir() {
        return dataDir.resolve("stories");
    }

    static Path draftsDir() {
        return dataDir.resolve("drafts");
    }

    // --- HTTP helpers ---------------------------------------------------------------------

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> post(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> getMap(String path) {
        return rest.getForEntity("http://localhost:" + port + path, Map.class);
    }

    @SuppressWarnings("rawtypes")
    ResponseEntity<List> getList(String path) {
        return rest.getForEntity("http://localhost:" + port + path, List.class);
    }

    @TestConfiguration
    static class FakeGeneratorConfig {
        @Bean
        @Primary
        FakeStoryDraftGenerator fakeStoryDraftGenerator() {
            return new FakeStoryDraftGenerator();
        }

        @Bean
        @Primary
        Clock fixedDraftsClock() {
            return Clock.fixed(Instant.parse("2026-10-06T10:00:00Z"), ZoneOffset.UTC);
        }
    }
}
