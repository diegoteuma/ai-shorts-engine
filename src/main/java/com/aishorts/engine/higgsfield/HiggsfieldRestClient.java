package com.aishorts.engine.higgsfield;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Cliente REST real contra la API de Higgsfield, usando java.net.http
 * (incluido en el JDK, sin dependencias externas).
 *
 * Los nombres de campo de submitGeneration/pollStatus están confirmados
 * contra el spec público (docs.higgsfield.ai/docs/openapi.json, schema
 * RequestStatus — el mismo para el response de submit y de status):
 * request_id, status_url y status vienen planos; el asset final viene
 * anidado como video.url (schema MediaOutput), nunca como un campo plano
 * output_url/url/asset_url. status es un enum cerrado de 6 valores: queued,
 * in_progress, nsfw, failed, completed, canceled — la interpretación de
 * esos 6 valores (qué es terminal, qué es fallo) vive en
 * {@link com.aishorts.engine.approval.StoryApprovalService#pollGenerationStatus},
 * no acá: este cliente solo parsea, no interpreta.
 *
 * Ese mismo spec confirma que NO existe ningún endpoint de estimate, así
 * que estimateCost no pega a la red — ver su javadoc.
 */
public final class HiggsfieldRestClient implements HiggsfieldClient {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final HiggsfieldConfig config;
    private final HttpClient http;
    private final ObjectMapper objectMapper;

    public HiggsfieldRestClient(HiggsfieldConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * El spec público de Higgsfield no tiene ningún endpoint de estimate
     * (confirmado leyendo docs.higgsfield.ai/docs/openapi.json completo:
     * los únicos paths son los de generación por modelo, /requests/{id}/status
     * y /requests/{id}/cancel). El costo se calcula localmente contra la
     * tabla de tarifas de {@link HiggsfieldConfig#pricingFor}, nunca por red.
     */
    @Override
    public EstimateResponse estimateCost(EstimateRequest request) throws HiggsfieldException {
        try {
            ModelPricing pricing = config.pricingFor(request.modelId());
            long requestedSeconds = ((Number) request.parameters().get("duration")).longValue();
            long billedSeconds = pricing.roundUpToAllowedDuration(requestedSeconds);
            BigDecimal cost = pricing.pricePerSecond().multiply(BigDecimal.valueOf(billedSeconds));
            Map<String, Object> raw = Map.of(
                    "source", "local-rate-table",
                    "pricePerSecond", pricing.pricePerSecond(),
                    "requestedDurationSeconds", requestedSeconds,
                    "billedDurationSeconds", billedSeconds
            );
            return new EstimateResponse(cost, pricing.currency(), request.modelId(), raw);
        } catch (IllegalArgumentException e) {
            throw new HiggsfieldException(
                    "No se pudo calcular el costo local para el modelo '" + request.modelId() + "': " + e.getMessage(), e);
        }
    }

    @Override
    public GenerationResponse submitGeneration(GenerationRequest request) throws HiggsfieldException {
        URI uri = URI.create(config.baseUrl() + "/" + request.modelId());
        Map<String, Object> json = post(uri, request.parameters());

        String requestId = extractString(json, "request_id", "id", "requestId");
        String statusUrl = extractString(json, "status_url", "statusUrl");
        String status = extractString(json, "status");
        if (requestId == null) {
            throw new HiggsfieldException(
                    "La respuesta de generación no trae un id de request reconocible "
                            + "(request_id/id/requestId). Respuesta cruda: " + json);
        }
        return new GenerationResponse(requestId, statusUrl, status != null ? status : "queued");
    }

    @Override
    public GenerationStatusResponse pollStatus(String statusUrl) throws HiggsfieldException {
        URI uri = URI.create(statusUrl);
        Map<String, Object> json = get(uri);

        String requestId = extractString(json, "request_id", "id", "requestId");
        String status = extractString(json, "status");
        // El asset final viene anidado como video: { url: "..." } (schema
        // MediaOutput), nunca como un campo plano — solo está presente una
        // vez que status es "completed".
        String outputUrl = extractNestedUrl(json, "video");
        String failureReason = extractString(json, "failure_reason", "error", "error_message");
        return new GenerationStatusResponse(requestId, status, outputUrl, failureReason);
    }

    // --- HTTP plumbing ------------------------------------------------------------------

    private Map<String, Object> post(URI uri, Map<String, Object> body) {
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .header("Authorization", "Key " + config.apiKeyId() + ":" + config.apiKeySecret())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(body)))
                .build();
        return send(httpRequest);
    }

    private String toJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new HiggsfieldException("Error serializando el request a JSON.", e);
        }
    }

    private Map<String, Object> get(URI uri) {
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .header("Authorization", "Key " + config.apiKeyId() + ":" + config.apiKeySecret())
                .GET()
                .build();
        return send(httpRequest);
    }

    private Map<String, Object> send(HttpRequest httpRequest) {
        try {
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new HiggsfieldException(
                        "Higgsfield respondió " + response.statusCode() + " para " + httpRequest.uri()
                                + ": " + response.body());
            }
            return objectMapper.readValue(response.body(), MAP_TYPE);
        } catch (IOException e) {
            throw new HiggsfieldException("Error de red llamando a " + httpRequest.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HiggsfieldException("Llamada a " + httpRequest.uri() + " interrumpida", e);
        }
    }

    // --- extracción tolerante de campos ---------------------------------------------------

    private static String extractString(Map<String, Object> json, String... candidateKeys) {
        for (String key : candidateKeys) {
            Object value = json.get(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    /** Lee json.get(key) como un objeto MediaOutput ({ url: "..." }) y devuelve su url, o null si no está. */
    private static String extractNestedUrl(Map<String, Object> json, String key) {
        Object value = json.get(key);
        if (value instanceof Map<?, ?> media) {
            Object url = media.get("url");
            return url != null ? String.valueOf(url) : null;
        }
        return null;
    }
}
