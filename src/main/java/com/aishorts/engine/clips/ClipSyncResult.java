package com.aishorts.engine.clips;

import java.util.List;
import java.util.Map;

/**
 * Resultado de intentar guardar los clips de una historia.
 *
 * @param attempted           false si no se intentó nada (no todas las escenas están COMPLETED)
 * @param storedSceneIds      clips descargados y validados en esta llamada
 * @param alreadyPresent      clips que ya estaban guardados y siguen siendo válidos
 * @param failedSceneIds      escena -> motivo; la escena queda COMPLETED con su URL y sin copia local
 */
public record ClipSyncResult(
        boolean attempted,
        List<String> storedSceneIds,
        List<String> alreadyPresent,
        Map<String, String> failedSceneIds
) {

    public static ClipSyncResult notAttempted() {
        return new ClipSyncResult(false, List.of(), List.of(), Map.of());
    }

    /** True si cambió el estado de alguna escena (hay algo que persistir). */
    public boolean changedScenes() {
        return !storedSceneIds.isEmpty() || !failedSceneIds.isEmpty();
    }
}
