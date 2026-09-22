package com.aishorts.engine.domain;

import java.util.List;

/**
 * Foto congelada de una Story completa (todas sus escenas, con su estado de
 * aprobación/costo/generación) — la unidad que realmente se persiste. Ver
 * {@link SceneSnapshot} para por qué esto es un record separado y no la
 * Story misma.
 */
public record StorySnapshot(String id, String topic, String title, List<SceneSnapshot> scenes) {
}
