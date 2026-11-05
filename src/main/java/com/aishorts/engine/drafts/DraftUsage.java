package com.aishorts.engine.drafts;

/**
 * Consumo acumulado de las llamadas a Claude que produjeron un borrador
 * (tokens, búsquedas web y caché de prompts).
 *
 * Con caché, la API reparte la entrada en tres campos que se suman:
 * inputTokens (lo que no se leyó ni se escribió en caché),
 * cacheCreationInputTokens (escrito en caché) y cacheReadInputTokens
 * (leído de caché). Sin caché, los dos últimos quedan en 0. Un borrador
 * guardado antes de que existieran esos campos se lee con 0.
 */
public record DraftUsage(long inputTokens, long outputTokens, long webSearchRequests,
                         long cacheCreationInputTokens, long cacheReadInputTokens) {

    public static final DraftUsage ZERO = new DraftUsage(0, 0, 0, 0, 0);

    /** Sin campos de caché (respuesta sin caché). */
    public DraftUsage(long inputTokens, long outputTokens, long webSearchRequests) {
        this(inputTokens, outputTokens, webSearchRequests, 0, 0);
    }

    public DraftUsage plus(DraftUsage other) {
        if (other == null) {
            return this;
        }
        return new DraftUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens,
                webSearchRequests + other.webSearchRequests,
                cacheCreationInputTokens + other.cacheCreationInputTokens,
                cacheReadInputTokens + other.cacheReadInputTokens);
    }
}
