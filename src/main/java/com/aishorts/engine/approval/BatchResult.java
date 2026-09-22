package com.aishorts.engine.approval;

import java.util.List;
import java.util.Map;

/**
 * Resultado de aplicar un lote de decisiones o de una consulta a Higgsfield.
 * Un rechazo o un error en una escena nunca frena a las demás del lote: cada
 * escena termina en succeeded o failed de forma independiente.
 */
public record BatchResult(List<String> succeededSceneIds, Map<String, String> failedSceneIds) {

    public boolean hasFailures() {
        return !failedSceneIds.isEmpty();
    }
}
