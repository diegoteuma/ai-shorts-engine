package com.aishorts.engine.tts;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.support.DefaultConversionService;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El body que se le manda a ElevenLabs, contra un servidor HTTP local: sin
 * voice settings configurados no viaja voice_settings (comportamiento de
 * siempre); con alguno, viaja solo lo configurado, con los nombres de la API.
 */
class ElevenLabsTtsServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RESPONSE = "{\"audio_base64\":\"" + Base64.getEncoder().encodeToString(new byte[]{1, 2, 3})
            + "\",\"alignment\":{\"characters\":[\"H\",\"o\"],\"character_start_times_seconds\":[0.0,0.1],"
            + "\"character_end_times_seconds\":[0.1,0.25]}}";

    private HttpServer server;
    private final List<Map<String, Object>> receivedBodies = new CopyOnWriteArrayList<>();
    private TtsConfig config;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/text-to-speech/voz-x/with-timestamps", this::route);
        server.start();
        config = new TtsConfig("clave", "voz-x", "eleven_multilingual_v2",
                "http://127.0.0.1:" + server.getAddress().getPort(), "mp3_44100_128", null);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void route(HttpExchange exchange) throws IOException {
        receivedBodies.add(MAPPER.readValue(exchange.getRequestBody().readAllBytes(), new TypeReference<>() {}));
        byte[] bytes = RESPONSE.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private Map<String, Object> send(TtsConfig ttsConfig) {
        TtsResult result = new ElevenLabsTtsService(ttsConfig, MAPPER).synthesize("Hola.");
        assertThat(result.duration()).isEqualTo(Duration.ofMillis(250));
        assertThat(receivedBodies).hasSize(1);
        return receivedBodies.get(0);
    }

    @Test
    void withoutVoiceSettings_theBodyHasNoVoiceSettings() {
        Map<String, Object> body = send(config);
        assertThat(body).containsOnlyKeys("text", "model_id", "output_format");
    }

    @Test
    void withAllFieldsNull_theBodyHasNoVoiceSettings() {
        Map<String, Object> body = send(config.withVoiceSettings(new TtsConfig.VoiceSettings(null, null, null, null, null)));
        assertThat(body).doesNotContainKey("voice_settings");
    }

    @Test
    void withVoiceSettings_onlyTheConfiguredFieldsAreSent() {
        Map<String, Object> body = send(config.withVoiceSettings(new TtsConfig.VoiceSettings(0.35, null, 0.3, null, null)));
        assertThat(body).containsKey("voice_settings");
        assertThat((Map<String, Object>) body.get("voice_settings")).containsExactlyInAnyOrderEntriesOf(
                Map.of("stability", 0.35, "style", 0.3));
    }

    @Test
    void withEveryField_namesMatchTheApi() {
        Map<String, Object> body = send(config.withVoiceSettings(new TtsConfig.VoiceSettings(0.4, 0.75, 0.2, true, 1.0)));
        assertThat((Map<String, Object>) body.get("voice_settings")).containsExactlyInAnyOrderEntriesOf(Map.of(
                "stability", 0.4, "similarity_boost", 0.75, "style", 0.2, "use_speaker_boost", true, "speed", 1.0));
    }

    /** application.yml deja ${ELEVENLABS_STABILITY:} vacío por defecto: Spring tiene que convertirlo a null, no fallar. */
    @Test
    void anEmptyEnvironmentVariable_becomesNull() {
        DefaultConversionService conversion = new DefaultConversionService();
        assertThat(conversion.convert("", Double.class)).isNull();
        assertThat(conversion.convert("", Boolean.class)).isNull();
    }
}
