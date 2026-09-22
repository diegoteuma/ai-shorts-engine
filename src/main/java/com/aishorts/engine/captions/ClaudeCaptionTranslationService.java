package com.aishorts.engine.captions;

import com.aishorts.engine.claude.ClaudeApiException;
import com.aishorts.engine.claude.ClaudeConfig;
import com.aishorts.engine.claude.ClaudeMessagesClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Traduce las 6 líneas de narración de una sola llamada (no una por escena)
 * para que el traductor tenga el contexto completo del Short y mantenga
 * coherencia de tono entre escenas.
 */
public final class ClaudeCaptionTranslationService implements CaptionTranslationService {

    private final ClaudeMessagesClient client;
    private final ObjectMapper objectMapper;

    public ClaudeCaptionTranslationService(ClaudeConfig config, ObjectMapper objectMapper) {
        this.client = new ClaudeMessagesClient(config, objectMapper);
        this.objectMapper = objectMapper;
    }

    @Override
    public List<String> translateToEnglish(List<String> narrationTextsEs) throws CaptionTranslationException {
        if (narrationTextsEs.isEmpty()) {
            return List.of();
        }

        String systemPrompt =
                "Sos traductor de subtítulos de español a inglés para un microdocumental de YouTube Shorts. "
                        + "Traducí cada línea a un inglés natural para lectura en pantalla, manteniendo el tono narrativo "
                        + "y el significado exacto — no resumas, no agregues ni quites información. "
                        + "Respondé ÚNICAMENTE con un array JSON de strings, en el mismo orden y con la misma cantidad "
                        + "de elementos que la lista de entrada, sin texto antes ni después, sin bloque de código markdown.";

        StringBuilder userMessage = new StringBuilder("Traducí estas ")
                .append(narrationTextsEs.size()).append(" líneas, una por una, en orden:\n");
        for (int i = 0; i < narrationTextsEs.size(); i++) {
            userMessage.append(i + 1).append(". ").append(narrationTextsEs.get(i)).append('\n');
        }

        String rawText;
        try {
            rawText = client.sendMessage(systemPrompt, userMessage.toString());
        } catch (ClaudeApiException e) {
            throw new CaptionTranslationException("Error llamando a la API de Claude para traducir subtítulos", e);
        }

        return parseTranslations(rawText, narrationTextsEs.size());
    }

    private List<String> parseTranslations(String rawText, int expectedCount) {
        String jsonText = ClaudeMessagesClient.stripMarkdownFence(rawText.trim());
        Object parsed;
        try {
            parsed = objectMapper.readValue(jsonText, Object.class);
        } catch (JsonProcessingException e) {
            throw new CaptionTranslationException(
                    "No se pudo parsear la traducción devuelta por Claude como JSON. Texto crudo:\n" + rawText, e);
        }
        if (!(parsed instanceof List<?> list)) {
            throw new CaptionTranslationException("Se esperaba un array JSON de strings. Texto crudo:\n" + rawText);
        }
        if (list.size() != expectedCount) {
            throw new CaptionTranslationException(
                    "Se esperaban " + expectedCount + " traducciones y llegaron " + list.size()
                            + ". Texto crudo:\n" + rawText);
        }
        List<String> translations = new ArrayList<>();
        for (Object item : list) {
            translations.add(String.valueOf(item));
        }
        return translations;
    }
}
