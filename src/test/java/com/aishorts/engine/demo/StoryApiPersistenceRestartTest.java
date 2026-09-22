package com.aishorts.engine.demo;

import com.aishorts.engine.captions.CaptionTranslationService;
import com.aishorts.engine.domain.GenerationStatus;
import com.aishorts.engine.domain.Story;
import com.aishorts.engine.duration.WordsPerSecondDurationEstimator;
import com.aishorts.engine.higgsfield.HiggsfieldClient;
import com.aishorts.engine.persistence.JsonFileStoryRepository;
import com.aishorts.engine.persistence.StoryRepository;
import com.aishorts.engine.script.ScriptDraftingService;
import com.aishorts.engine.tts.TtsService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Ejercita la API REST completa (las dos puertas humanas, de punta a punta,
 * vía HTTP real) y después verifica que la persistencia sobrevive un
 * reinicio de proceso simulado — igual que el test manual que documenta el
 * README ("Persistencia", "API REST"): crear una historia, llevarla a través
 * de las dos puertas y la generación, cerrar el repositorio y abrir una
 * instancia NUEVA apuntando al mismo directorio, y confirmar que se relee
 * con exactamente el mismo estado.
 *
 * Reemplaza los cuatro beans "reales" que EngineConfiguration conecta a
 * servicios pagos (HiggsfieldClient, TtsService, ScriptDraftingService,
 * CaptionTranslationService) por los Fake* del paquete demo — @Primary les
 * gana a los beans reales sin tocar EngineConfiguration, así que esto no
 * gasta ni depende de ninguna red real, igual que DemoRunner. Vive en el
 * paquete demo porque los Fake* son package-private a propósito (no son API
 * pública, solo existen para simular el flujo sin red).
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(StoryApiPersistenceRestartTest.FakeServicesConfig.class)
class StoryApiPersistenceRestartTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void engineProperties(DynamicPropertyRegistry registry) {
        registry.add("DATA_DIR", () -> dataDir.resolve("stories").toString());
        registry.add("AUDIO_DIR", () -> dataDir.resolve("audio").toString());
        // Credenciales dummy: nunca se usan de verdad (los Fake* las reemplazan),
        // solo hacen falta para que EngineConfiguration pueda resolver sus
        // @Value y construir sus beans "reales" (que quedan sin usar, ver @Primary).
        registry.add("CLAUDE_API_KEY", () -> "test-claude-key");
        registry.add("CLAUDE_MODEL", () -> "test-claude-model");
        registry.add("HIGGSFIELD_BASE_URL", () -> "https://higgsfield.invalid");
        registry.add("HIGGSFIELD_API_KEY_ID", () -> "test-id");
        registry.add("HIGGSFIELD_API_KEY_SECRET", () -> "test-secret");
        registry.add("HIGGSFIELD_MODEL_STANDARD", () -> "higgsfield-ai/soul/standard");
        registry.add("HIGGSFIELD_MODEL_PREMIUM", () -> "higgsfield-ai/soul-premium/cinema");
        registry.add("ELEVENLABS_API_KEY", () -> "test-elevenlabs-key");
        registry.add("ELEVENLABS_VOICE_ID", () -> "test-voice");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private StoryRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void storyPersistsAcrossASimulatedProcessRestart() {
        String storyId = "story-1";

        Map<String, Object> createBody = Map.of(
                "storyId", storyId,
                "topic", "Explosión de Tunguska (1908)",
                "title", "La explosión que derribó 80 millones de árboles",
                "coreFacts", List.of("El 30 de junio de 1908 un objeto espacial explotó en la atmósfera sobre Siberia."),
                "durationBudget", Map.of("minSeconds", 20, "maxSeconds", 60)
        );
        Map<String, Object> created = post("/stories", createBody, 201);
        List<String> sceneIds = sceneIds(created);
        assertThat(sceneIds).hasSize(6);

        // Puerta 1: proponer tier, después aprobar el lote completo de prompts.
        Map<String, Object> tiersBody = new LinkedHashMap<>();
        for (String sceneId : sceneIds) {
            tiersBody.put(sceneId, Map.of(
                    "movementComplexity", 5, "elementCount", 5, "physicalRealism", 5, "cameraDynamism", 5));
        }
        post("/stories/" + storyId + "/tiers", tiersBody, 200);
        post("/stories/" + storyId + "/prompt-decisions", approveAll(sceneIds), 200);

        // Entre puertas: narración real (Fake) y estimación de costo real (Fake).
        post("/stories/" + storyId + "/narration", null, 200);
        post("/stories/" + storyId + "/cost-estimates", null, 200);

        // Puerta 2: aprobar el lote completo de costos.
        post("/stories/" + storyId + "/cost-decisions", approveAll(sceneIds), 200);

        // Generación + polling hasta GENERATED (el Fake completa en el primer poll).
        post("/stories/" + storyId + "/generate", null, 200);
        Map<String, Object> polled = post("/stories/" + storyId + "/poll-generation", null, 200);

        @SuppressWarnings("unchecked")
        Map<String, Object> storyAfterPoll = (Map<String, Object>) polled.get("story");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scenesAfterPoll = (List<Map<String, Object>>) storyAfterPoll.get("scenes");
        assertThat(scenesAfterPoll).hasSize(6);
        for (Map<String, Object> scene : scenesAfterPoll) {
            assertThat(scene.get("generationStatus")).isEqualTo("COMPLETED");
        }

        // --- "antes del reinicio": la Story tal como la dejó el último request ---
        Story beforeRestart = repository.findById(storyId).orElseThrow();
        assertThat(beforeRestart.scenes()).hasSize(6);
        assertThat(beforeRestart.scenes()).allSatisfy(scene -> {
            assertThat(scene.generationStatus()).isEqualTo(GenerationStatus.COMPLETED);
            assertThat(scene.hasNarrationAudio()).isTrue();
            assertThat(scene.narrationAudioPath()).isNotBlank();
            assertThat(scene.generatedAssetUrl()).isNotBlank();
        });

        // --- "reinicio del proceso": una instancia NUEVA de JsonFileStoryRepository,
        // sin relación con la que usó la API, apuntando al mismo directorio ---
        // JsonFileStoryRepository no mantiene handles abiertos ni estado propio
        // entre llamadas (cada save/findById es una escritura/lectura atómica a
        // disco), así que una instancia nueva es equivalente a "el proceso volvió
        // a arrancar y releyó lo que había en dataDir".
        StoryRepository reopened = new JsonFileStoryRepository(dataDir.resolve("stories"), objectMapper);
        Story afterRestart = reopened.findById(storyId).orElseThrow();

        assertThat(afterRestart.toSnapshot()).isEqualTo(beforeRestart.toSnapshot());
        assertThat(afterRestart.scenes()).hasSize(6);
        assertThat(afterRestart.scenes()).allSatisfy(scene -> {
            assertThat(scene.generationStatus()).isEqualTo(GenerationStatus.COMPLETED);
            assertThat(scene.hasNarrationAudio()).isTrue();
            assertThat(scene.narrationAudioPath()).isNotBlank();
            assertThat(scene.generatedAssetUrl()).isNotBlank();
        });
    }

    // --- helpers HTTP -----------------------------------------------------------------------

    private List<Map<String, Object>> approveAll(List<String> sceneIds) {
        return sceneIds.stream()
                .map(id -> Map.<String, Object>of("sceneId", id, "decision", "APPROVE"))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> sceneIds(Map<String, Object> storyMap) {
        List<Map<String, Object>> scenes = (List<Map<String, Object>>) storyMap.get("scenes");
        return scenes.stream().map(s -> (String) s.get("id")).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Object body, int expectedStatus) {
        ResponseEntity<Map> response = restTemplate.postForEntity(baseUrl(path), body, Map.class);
        assertThat(response.getStatusCode().value())
                .as("POST %s -> %s: %s", path, response.getStatusCode(), response.getBody())
                .isEqualTo(expectedStatus);
        return (Map<String, Object>) response.getBody();
    }

    private String baseUrl(String path) {
        return "http://localhost:" + port + path;
    }

    // --- reemplazo de los servicios pagos por los Fake* del paquete demo --------------------

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

        @Bean
        @Primary
        CaptionTranslationService fakeCaptionTranslationService() {
            return new FakeCaptionTranslationService();
        }
    }
}
