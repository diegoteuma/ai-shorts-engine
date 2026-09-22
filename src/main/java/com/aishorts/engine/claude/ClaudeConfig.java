package com.aishorts.engine.claude;

import java.util.Objects;

/**
 * Configuración compartida para cualquier llamada a la API de Claude
 * (guionado, traducción de subtítulos, y lo que se agregue después).
 *
 * model: confirmar el identificador vigente contra docs.claude.com antes de
 * desplegar — los nombres de modelo de la API cambian con el tiempo y no
 * conviene confiar en un valor grabado acá sin revisar.
 */
public record ClaudeConfig(String apiKey, String model, String baseUrl, int maxTokens) {
    public ClaudeConfig {
        Objects.requireNonNull(apiKey, "apiKey");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(baseUrl, "baseUrl");
    }

    public static ClaudeConfig of(String apiKey, String model) {
        return new ClaudeConfig(apiKey, model, "https://api.anthropic.com", 4096);
    }
}
