package com.aishorts.engine.script;

import com.aishorts.engine.domain.SceneRole;

/** Una escena tal como la propone el servicio de guionado, antes de que exista un Scene real. */
public record SceneDraft(SceneRole role, String narrationText, String visualPrompt) {
}
