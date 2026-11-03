package com.aishorts.engine.tts;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * voiceId: el id de una voz en español neutro de la librería de ElevenLabs
 * — se elige desde su consola/API de voces, no hay un default razonable
 * para grabar acá.
 * modelId: eleven_multilingual_v2 (recomendado para narración) o eleven_v3.
 * voiceSettings: null = no se envía voice_settings y la voz usa sus valores
 * guardados (el comportamiento de siempre).
 */
public record TtsConfig(String apiKey, String voiceId, String modelId, String baseUrl, String outputFormat,
                        VoiceSettings voiceSettings) {
    public TtsConfig {
        Objects.requireNonNull(apiKey, "apiKey");
        Objects.requireNonNull(voiceId, "voiceId");
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(outputFormat, "outputFormat");
    }

    public static TtsConfig of(String apiKey, String voiceId) {
        return new TtsConfig(apiKey, voiceId, "eleven_multilingual_v2", "https://api.elevenlabs.io", "mp3_44100_128", null);
    }

    public TtsConfig withVoiceSettings(VoiceSettings settings) {
        return new TtsConfig(apiKey, voiceId, modelId, baseUrl, outputFormat, settings);
    }

    /**
     * Objeto voice_settings del body de POST /v1/text-to-speech/{voice_id}/with-timestamps
     * (https://elevenlabs.io/docs/api-reference/text-to-speech/convert-with-timestamps). Cada
     * campo null no se envía; si todos son null no se envía voice_settings. Defaults de la API
     * según esa página: stability 0.5, similarity_boost 0.75, style 0, use_speaker_boost true,
     * speed 1. La documentación no publica rangos ni qué campos aplica cada modelo, así que acá
     * no se valida nada: ElevenLabs responde con error si un valor no le sirve.
     */
    public record VoiceSettings(Double stability, Double similarityBoost, Double style, Boolean useSpeakerBoost,
                                Double speed) {

        /** Los campos no nulos, con los nombres de la API; vacío si no hay ninguno. */
        public Map<String, Object> toRequestFields() {
            Map<String, Object> fields = new LinkedHashMap<>();
            putIfPresent(fields, "stability", stability);
            putIfPresent(fields, "similarity_boost", similarityBoost);
            putIfPresent(fields, "style", style);
            putIfPresent(fields, "use_speaker_boost", useSpeakerBoost);
            putIfPresent(fields, "speed", speed);
            return fields;
        }

        private static void putIfPresent(Map<String, Object> fields, String name, Object value) {
            if (value != null) {
                fields.put(name, value);
            }
        }
    }
}
