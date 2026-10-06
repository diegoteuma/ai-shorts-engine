package com.aishorts.engine.drafts;

/** Consumo acumulado de las llamadas a Claude que produjeron un borrador (tokens y búsquedas web). */
public record DraftUsage(long inputTokens, long outputTokens, long webSearchRequests) {

    public static final DraftUsage ZERO = new DraftUsage(0, 0, 0);

    public DraftUsage plus(DraftUsage other) {
        if (other == null) {
            return this;
        }
        return new DraftUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens,
                webSearchRequests + other.webSearchRequests);
    }
}
