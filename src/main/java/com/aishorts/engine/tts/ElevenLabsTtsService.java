package com.aishorts.engine.tts;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * POST /v1/text-to-speech/{voice_id}/with-timestamps — a diferencia del
 * cliente de Higgsfield, acá el esquema de la respuesta SÍ está documentado
 * con precisión (audio_base64 + alignment.character_end_times_seconds), así
 * que este parseo no necesita la extracción "tolerante" que usamos allá.
 */
public final class ElevenLabsTtsService implements TtsService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final TtsConfig config;
    private final HttpClient http;
    private final ObjectMapper objectMapper;

    public ElevenLabsTtsService(TtsConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    @Override
    public TtsResult synthesize(String narrationText) throws TtsException {
        if (narrationText == null || narrationText.isBlank()) {
            throw new TtsException("narrationText no puede estar vacío.");
        }

        Map<String, Object> body = Map.of(
                "text", narrationText,
                "model_id", config.modelId(),
                "output_format", config.outputFormat()
        );

        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(config.baseUrl() + "/v1/text-to-speech/" + config.voiceId() + "/with-timestamps"))
                    .header("xi-api-key", config.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new TtsException("ElevenLabs respondió " + response.statusCode() + ": " + response.body());
            }
            return parse(response.body());
        } catch (IOException e) {
            throw new TtsException("Error de red llamando a ElevenLabs", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TtsException("Llamada a ElevenLabs interrumpida", e);
        }
    }

    private TtsResult parse(String responseBody) {
        Map<String, Object> json;
        try {
            json = objectMapper.readValue(responseBody, MAP_TYPE);
        } catch (IOException e) {
            throw new TtsException("No se pudo parsear la respuesta de ElevenLabs como JSON. Cruda: " + responseBody, e);
        }

        Object audioB64 = json.get("audio_base64");
        if (!(audioB64 instanceof String audioText)) {
            throw new TtsException("La respuesta de ElevenLabs no trae 'audio_base64'. Cruda: " + responseBody);
        }
        byte[] audioBytes = Base64.getDecoder().decode(audioText);

        Object alignmentObj = json.get("alignment");
        if (!(alignmentObj instanceof Map<?, ?> alignment)) {
            throw new TtsException("La respuesta de ElevenLabs no trae 'alignment'. Cruda: " + responseBody);
        }
        Object endTimesObj = alignment.get("character_end_times_seconds");
        if (!(endTimesObj instanceof List<?> endTimes) || endTimes.isEmpty()) {
            throw new TtsException(
                    "La respuesta de ElevenLabs no trae 'character_end_times_seconds' o vino vacío. Cruda: " + responseBody);
        }
        Object lastEndTime = endTimes.get(endTimes.size() - 1);
        BigDecimal lastSeconds = (lastEndTime instanceof BigDecimal decimal) ? decimal : new BigDecimal(String.valueOf(lastEndTime));
        long millis = Math.round(lastSeconds.doubleValue() * 1000);

        return new TtsResult(audioBytes, Duration.ofMillis(millis));
    }
}
