package com.aishorts.engine.script;

import java.util.List;

/**
 * Puerto hacia el LLM que redacta el guion. Devuelve exactamente 6
 * SceneDraft, uno por cada SceneRole en el orden narrativo GANCHO,
 * EXPLICACION, CONTEXTO, GIRO, CONSECUENCIA, CIERRE — nunca aprueba ni
 * genera nada por sí solo, solo propone texto para que vos lo revises en la
 * puerta 1.
 */
public interface ScriptDraftingService {
    List<SceneDraft> draftScenes(StoryBrief brief) throws ScriptDraftingException;
}
