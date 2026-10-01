package com.aishorts.engine.higgsfield;

import com.aishorts.engine.approval.StoryApprovalService;
import com.aishorts.engine.difficulty.DefaultSceneDifficultyScorer;
import com.aishorts.engine.difficulty.TierRecommendation;
import com.aishorts.engine.domain.GenerationStatus;
import com.aishorts.engine.domain.GenerationTier;
import com.aishorts.engine.domain.Scene;
import com.aishorts.engine.domain.SceneRole;
import com.aishorts.engine.domain.Story;
import com.aishorts.engine.duration.WordsPerSecondDurationEstimator;
import com.aishorts.engine.tts.TtsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cubre las tres discrepancias encontradas al revisar
 * docs.higgsfield.ai/docs/openapi.json contra el código de este paquete:
 *
 * 1) No existe ningún endpoint de estimate real -> estimateCost calcula
 *    localmente, sin red (contra un baseUrl al que nada escucha, para
 *    probar justamente que no intenta pegarle a la red).
 * 2) El asset final viene anidado como video.url (schema MediaOutput), no
 *    como un campo plano -> pollStatus tiene que leerlo de ahí.
 * 3) El status real tiene 6 valores posibles (queued, in_progress, nsfw,
 *    failed, completed, canceled) -> nsfw/canceled tienen que terminar la
 *    escena en FAILED, nunca quedar poleados indefinidamente como "en curso".
 * 4) Seedance 2.0 genera audio propio por defecto salvo que se le pida
 *    "generate_audio": false explícitamente -> se paga por un audio que
 *    después se descarta. STANDARD y PREMIUM comparten el mismo modelo (ver
 *    KnownHiggsfieldPricing), así que esto aplica a los dos tiers por igual.
 *
 * Los tests de status usan un servidor HTTP local real (mismo patrón que
 * VideoMontageBuilderFfmpegTest) que devuelve las respuestas exactamente
 * como las documenta el spec.
 */
class HiggsfieldRestClientTest {

    private static final String STANDARD_MODEL_ID = KnownHiggsfieldPricing.STANDARD_MODEL_ID;
    private static final String PREMIUM_MODEL_ID = KnownHiggsfieldPricing.PREMIUM_MODEL_ID;
    private static final String WAN_MODEL_ID = KnownHiggsfieldPricing.WAN_PRIME_MODEL_ID;

    private static HttpServer server;
    private static int port;
    private static HiggsfieldConfig config;

    /**
     * Bodies de submitGeneration capturados por el server fake — ver fix 4
     * (generate_audio false). Lista, no Map por modelId: STANDARD y PREMIUM
     * comparten el mismo model_id (ver KnownHiggsfieldPricing), así que una
     * clave por modelId perdería el segundo body al pisar al primero.
     */
    private static final List<String> capturedGenerationRequestBodies = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", HiggsfieldRestClientTest::route);
        server.setExecutor(null);
        server.start();
        port = server.getAddress().getPort();

        // config.baseUrl() no lo usa ni estimateCost (ya no pega a la red) ni
        // pollStatus (recibe la statusUrl absoluta) -- apunta al server fake
        // solo para que quede consistente si algo más lo llegara a leer.
        config = new HiggsfieldConfig(
                baseUrl(), "key-id", "key-secret",
                Map.of(
                        GenerationTier.STANDARD, STANDARD_MODEL_ID,
                        GenerationTier.PREMIUM, PREMIUM_MODEL_ID
                ),
                KnownHiggsfieldPricing.defaults());
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    // --- fix 1: estimateCost calcula localmente, nunca pega a la red -----------------------
    // STANDARD y PREMIUM comparten el mismo modelo (Seedance 2.0) y por lo
    // tanto el mismo DurationPolicy.ContinuousRange [4, 15] -- a diferencia
    // del viejo esquema Kling, donde STANDARD era un enum fijo {5, 10} y
    // PREMIUM un rango [3, 15] propio.

    @Test
    void estimateCost_computesLocallyWithoutHittingNetwork() {
        HiggsfieldRestClient client = clientWithUnreachableBaseUrl();

        EstimateResponse exact = client.estimateCost(new EstimateRequest(
                STANDARD_MODEL_ID, Map.of("duration", 5L, "prompt", "x", "aspect_ratio", "9:16")));
        assertThat(exact.cost()).isEqualByComparingTo(new BigDecimal("1.75")); // 0.35 * 5
        assertThat(exact.currency()).isEqualTo("USD");

        EstimateResponse rounded = client.estimateCost(new EstimateRequest(
                STANDARD_MODEL_ID, Map.of("duration", 2L, "prompt", "x", "aspect_ratio", "9:16")));
        assertThat(rounded.cost()).isEqualByComparingTo(new BigDecimal("1.40")); // 2s -> redondea al mínimo 4 -> 0.35 * 4
    }

    @Test
    void estimateCost_throwsWhenDurationExceedsWhatTheModelAllows() {
        HiggsfieldRestClient client = clientWithUnreachableBaseUrl();

        assertThatThrownBy(() -> client.estimateCost(new EstimateRequest(
                STANDARD_MODEL_ID, Map.of("duration", 20L, "prompt", "x", "aspect_ratio", "9:16"))))
                .isInstanceOf(HiggsfieldException.class)
                .hasMessageContaining("15");
    }

    @Test
    void estimateCost_throwsWhenNoPricingConfiguredForModel() {
        HiggsfieldRestClient client = clientWithUnreachableBaseUrl();

        assertThatThrownBy(() -> client.estimateCost(new EstimateRequest(
                "higgsfield-ai/some-future-model/not-priced-yet", Map.of("duration", 5L))))
                .isInstanceOf(HiggsfieldException.class)
                .hasMessageContaining("No hay tarifa configurada");
    }

    /**
     * STANDARD y PREMIUM están unificados a propósito en el mismo model_id
     * de Higgsfield (ver KnownHiggsfieldPricing) -- confirma que eso no
     * revienta la construcción de HiggsfieldConfig (dos tiers, un solo
     * modelId: pricingByModelId no puede tener dos entradas con la misma
     * key) y que PREMIUM_MODEL_ID resuelve exactamente la misma tarifa que
     * STANDARD_MODEL_ID, no una copia que pueda divergir. pricingByModelId
     * tiene 2 entradas en total: la compartida de Seedance 2.0 (STANDARD +
     * PREMIUM) más la de Wan 3.0 Prime (ver WAN_PRIME_MODEL_ID más abajo).
     */
    @Test
    void standardAndPremium_resolveToTheSameSharedPricing() {
        assertThat(PREMIUM_MODEL_ID).isEqualTo(STANDARD_MODEL_ID);
        assertThat(config.pricingByModelId()).hasSize(2);

        HiggsfieldRestClient client = clientWithUnreachableBaseUrl();
        EstimateResponse viaStandard = client.estimateCost(new EstimateRequest(
                STANDARD_MODEL_ID, Map.of("duration", 5L, "prompt", "x", "aspect_ratio", "9:16")));
        EstimateResponse viaPremium = client.estimateCost(new EstimateRequest(
                PREMIUM_MODEL_ID, Map.of("duration", 5L, "prompt", "x", "aspect_ratio", "9:16")));

        assertThat(viaPremium.cost()).isEqualByComparingTo(viaStandard.cost());
    }

    // --- Wan 3.0 Prime: tarifa nueva agregada sin sacar la de Seedance ---------------------

    @Test
    void estimateCost_computesLocallyForWanPrime() {
        HiggsfieldRestClient client = clientWithUnreachableBaseUrl();

        EstimateResponse exact = client.estimateCost(new EstimateRequest(
                WAN_MODEL_ID, Map.of("duration", 5L, "prompt", "x", "aspect_ratio", "9:16")));
        assertThat(exact.cost()).isEqualByComparingTo(new BigDecimal("0.70")); // 0.14 * 5

        EstimateResponse atMinimum = client.estimateCost(new EstimateRequest(
                WAN_MODEL_ID, Map.of("duration", 2L, "prompt", "x", "aspect_ratio", "9:16")));
        assertThat(atMinimum.cost()).isEqualByComparingTo(new BigDecimal("0.28")); // 2s exacto (mínimo), sin redondeo -> 0.14 * 2

        assertThatThrownBy(() -> client.estimateCost(new EstimateRequest(
                WAN_MODEL_ID, Map.of("duration", 31L, "prompt", "x", "aspect_ratio", "9:16"))))
                .isInstanceOf(HiggsfieldException.class)
                .hasMessageContaining("30"); // excede el máximo del rango [2, 30]
    }

    /**
     * Wan defaultea a resolution "1080p" (el doble de precio que "720p") y
     * aspect_ratio "adaptive" si no se mandan explícitos -- confirma que
     * buildGenerationParameters los pisa siempre, para este modelo
     * puntualmente, no solo para Seedance. duration también tiene que viajar
     * como entero JSON (sin decimales): el campo duration de Wan es integer.
     */
    @Test
    void generateApprovedScenes_forWanPrime_sendsExplicitResolutionAndAspectRatio() {
        capturedGenerationRequestBodies.clear();
        Scene scene = costApprovedScene("scene-wan", GenerationTier.STANDARD, WAN_MODEL_ID, 1);
        Story story = new Story("story-wan", "topic", "title", List.of(scene));

        approvalService().generateApprovedScenes(story);

        assertThat(capturedGenerationRequestBodies).hasSize(1);
        String body = capturedGenerationRequestBodies.get(0);
        assertThat(body).contains("\"resolution\":\"720p\"");
        assertThat(body).contains("\"aspect_ratio\":\"9:16\"");
        assertThat(body).contains("\"generate_audio\":false");
        assertThat(body).contains("\"duration\":5");
        assertThat(body).doesNotContain("\"duration\":5.0").doesNotContain("\"duration\":\"5\"");
    }

    // --- fix 4: "generate_audio": false para STANDARD y PREMIUM ---------------------------

    @Test
    void generateApprovedScenes_sendsGenerateAudioFalse_forBothTiers() {
        capturedGenerationRequestBodies.clear();
        Scene standardScene = costApprovedScene("scene-standard", GenerationTier.STANDARD, STANDARD_MODEL_ID, 1);
        Scene premiumScene = costApprovedScene("scene-premium", GenerationTier.PREMIUM, PREMIUM_MODEL_ID, 1);
        Story standardStory = new Story("story-standard", "topic", "title", List.of(standardScene));
        Story premiumStory = new Story("story-premium", "topic", "title", List.of(premiumScene));

        approvalService().generateApprovedScenes(standardStory);
        approvalService().generateApprovedScenes(premiumStory);

        // Dos requests (uno por tier) contra el mismo model_id compartido --
        // cada uno tiene que llevar generate_audio:false independientemente
        // de que la escena haya elegido STANDARD o PREMIUM.
        assertThat(capturedGenerationRequestBodies).hasSize(2);
        assertThat(capturedGenerationRequestBodies).allSatisfy(
                body -> assertThat(body).contains("\"generate_audio\":false"));
    }

    /** Una Scene con costo APPROVED (generationStatus NOT_STARTED), lista para generateApprovedScenes. */
    private Scene costApprovedScene(String id, GenerationTier tier, String modelId, int order) {
        Scene scene = new Scene(id, SceneRole.GANCHO, order, "narración " + id, "prompt " + id, Duration.ofSeconds(5));
        scene.proposeTier(new TierRecommendation(tier, 20, "test"));
        scene.approvePrompt();
        scene.recordCostEstimate(new EstimateResponse(new BigDecimal("0.105"), "USD", modelId, Map.of()), modelId);
        scene.approveCost();
        return scene;
    }

    private static HiggsfieldRestClient clientWithUnreachableBaseUrl() {
        HiggsfieldConfig unreachable = new HiggsfieldConfig(
                "http://127.0.0.1:1", "key-id", "key-secret",
                config.modelCatalog(), config.pricingByModelId());
        return new HiggsfieldRestClient(unreachable, new ObjectMapper());
    }

    // --- fix 2: pollStatus lee el asset anidado en video.url --------------------------------

    @Test
    void pollStatus_extractsNestedVideoUrl_whenCompleted() {
        HiggsfieldRestClient client = new HiggsfieldRestClient(config, new ObjectMapper());

        GenerationStatusResponse status = client.pollStatus(baseUrl() + "/requests/req-completed/status");

        assertThat(status.status()).isEqualTo("completed");
        assertThat(status.requestId()).isEqualTo("req-completed");
        assertThat(status.outputUrl()).isEqualTo("https://cdn.higgsfield.ai/assets/req-completed.mp4");
    }

    // --- fix 3: los 6 status reales, vía StoryApprovalService.pollGenerationStatus ----------

    @Test
    void pollGenerationStatus_completesScene_extractingNestedVideoUrl() {
        Scene scene = queuedScene("scene-completed", "req-completed", 1);
        Story story = new Story("story-1", "topic", "title", List.of(scene));

        approvalService().pollGenerationStatus(story);

        assertThat(scene.generationStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(scene.generatedAssetUrl()).isEqualTo("https://cdn.higgsfield.ai/assets/req-completed.mp4");
    }

    @Test
    void pollGenerationStatus_failsScene_whenHiggsfieldReportsNsfw() {
        Scene scene = queuedScene("scene-nsfw", "req-nsfw", 1);
        Story story = new Story("story-1", "topic", "title", List.of(scene));

        approvalService().pollGenerationStatus(story);

        assertThat(scene.generationStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(scene.generationFailureReason()).containsIgnoringCase("nsfw");
    }

    @Test
    void pollGenerationStatus_failsScene_whenHiggsfieldReportsCanceled() {
        Scene scene = queuedScene("scene-canceled", "req-canceled", 1);
        Story story = new Story("story-1", "topic", "title", List.of(scene));

        approvalService().pollGenerationStatus(story);

        assertThat(scene.generationStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(scene.generationFailureReason()).containsIgnoringCase("cancel");
    }

    @Test
    void pollGenerationStatus_failsScene_whenHiggsfieldReportsFailed() {
        Scene scene = queuedScene("scene-failed", "req-failed", 1);
        Story story = new Story("story-1", "topic", "title", List.of(scene));

        approvalService().pollGenerationStatus(story);

        assertThat(scene.generationStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(scene.generationFailureReason()).isEqualTo("modelo rechazó el prompt");
    }

    @Test
    void pollGenerationStatus_keepsScenesPollable_whenHiggsfieldReportsQueuedOrInProgress() {
        Scene queued = queuedScene("scene-queued", "req-queued", 1);
        Scene inProgress = queuedScene("scene-inprogress", "req-inprogress", 2);
        Story story = new Story("story-1", "topic", "title", List.of(queued, inProgress));

        approvalService().pollGenerationStatus(story);

        // Ninguna quedó en un estado terminal: el próximo poll las va a seguir consultando,
        // a diferencia de nsfw/canceled más arriba.
        assertThat(queued.generationStatus()).isEqualTo(GenerationStatus.IN_PROGRESS);
        assertThat(inProgress.generationStatus()).isEqualTo(GenerationStatus.IN_PROGRESS);
        assertThat(story.allGenerationFinished()).isFalse();
    }

    // --- helpers ----------------------------------------------------------------------------

    /** Una Scene ya en QUEUED, lista para que pollGenerationStatus la consulte. */
    private Scene queuedScene(String id, String requestId, int order) {
        Scene scene = new Scene(id, SceneRole.GANCHO, order, "narración " + id, "prompt " + id, Duration.ofSeconds(5));
        scene.proposeTier(new TierRecommendation(GenerationTier.STANDARD, 20, "test"));
        scene.approvePrompt();
        scene.recordCostEstimate(new EstimateResponse(new BigDecimal("0.105"), "USD", STANDARD_MODEL_ID, Map.of()), STANDARD_MODEL_ID);
        scene.approveCost();
        scene.startGeneration(requestId, baseUrl() + "/requests/" + requestId + "/status");
        return scene;
    }

    private StoryApprovalService approvalService() {
        HiggsfieldRestClient client = new HiggsfieldRestClient(config, new ObjectMapper());
        TtsService neverCalledTts = text -> {
            throw new UnsupportedOperationException("No debería llamarse: pollGenerationStatus no sintetiza audio.");
        };
        return new StoryApprovalService(
                client, config, new DefaultSceneDifficultyScorer(),
                WordsPerSecondDurationEstimator.neutralSpanish(), neverCalledTts);
    }

    private static String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    // --- servidor HTTP local: devuelve las respuestas de /requests/{id}/status tal como
    // las documenta el spec (schema RequestStatus) -----------------------------------------

    private static void route(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            // submitGeneration hace POST {baseUrl}/{modelId} -- capturamos el body para
            // poder confirmar qué parámetros mandó de verdad (fix 4: generate_audio false).
            // Un solo check: STANDARD_MODEL_ID y PREMIUM_MODEL_ID son el mismo
            // literal a propósito (ver KnownHiggsfieldPricing), así que un
            // segundo "if" idéntico sería código muerto.
            if ("POST".equals(method) && ("/" + STANDARD_MODEL_ID).equals(path)) {
                captureGenerationRequestAndRespond(exchange, STANDARD_MODEL_ID);
                return;
            }
            // WAN_MODEL_ID es un literal distinto de STANDARD_MODEL_ID (a
            // diferencia de PREMIUM_MODEL_ID arriba), así que acá sí hace
            // falta un segundo check real.
            if ("POST".equals(method) && ("/" + WAN_MODEL_ID).equals(path)) {
                captureGenerationRequestAndRespond(exchange, WAN_MODEL_ID);
                return;
            }

            String body = switch (path) {
                case "/requests/req-completed/status" -> """
                        {"status":"completed","request_id":"req-completed","status_url":"http://example/requests/req-completed/status",\
                        "video":{"url":"https://cdn.higgsfield.ai/assets/req-completed.mp4"}}""";
                case "/requests/req-nsfw/status" -> """
                        {"status":"nsfw","request_id":"req-nsfw","error":null}""";
                case "/requests/req-canceled/status" -> """
                        {"status":"canceled","request_id":"req-canceled","error":null}""";
                case "/requests/req-failed/status" -> """
                        {"status":"failed","request_id":"req-failed","error":"modelo rechazó el prompt"}""";
                case "/requests/req-queued/status" -> """
                        {"status":"queued","request_id":"req-queued"}""";
                case "/requests/req-inprogress/status" -> """
                        {"status":"in_progress","request_id":"req-inprogress"}""";
                default -> null;
            };
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            respond(exchange, body);
        } finally {
            exchange.close();
        }
    }

    private static void captureGenerationRequestAndRespond(HttpExchange exchange, String modelId) throws IOException {
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        capturedGenerationRequestBodies.add(requestBody);
        respond(exchange, """
                {"status":"queued","request_id":"req-%s","status_url":"%s"}\
                """.formatted(modelId.replace('/', '-'), baseUrl() + "/requests/req-generated/status"));
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
