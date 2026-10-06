package com.aishorts.engine.drafts;

/** Lo mínimo de una historia (o borrador) ya existente para evitar repetir temas: se le pasa a Claude como existingStories. */
public record ExistingStory(String id, String title, String topic) {
}
