package com.aishorts.engine.tts;

import java.util.Objects;

/**
 * voiceId: el id de una voz en español neutro de la librería de ElevenLabs
 * — se elige desde su consola/API de voces, no hay un default razonable
 * para grabar acá.
 * modelId: eleven_multilingual_v2 (recomendado para narración) o eleven_v3.
 */
public record TtsConfig(String apiKey, String voiceId, String modelId, String baseUrl, String outputFormat) {
    public TtsConfig {
        Objects.requireNonNull(apiKey, "apiKey");
        Objects.requireNonNull(voiceId, "voiceId");
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(outputFormat, "outputFormat");
    }

    public static TtsConfig of(String apiKey, String voiceId) {
        return new TtsConfig(apiKey, voiceId, "eleven_multilingual_v2", "https://api.elevenlabs.io", "mp3_44100_128");
    }
}
