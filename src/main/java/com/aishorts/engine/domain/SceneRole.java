package com.aishorts.engine.domain;

/**
 * Rol narrativo de una escena dentro de la estructura REAL / IF del Short.
 * El peso narrativo (usado por el scorer de dificultad) refleja cuánto
 * sostiene cada parte la retención del video: GANCHO, GIRO y CONSECUENCIA
 * son las escenas de mayor impacto visual; CONTEXTO y EXPLICACION son de
 * transición y normalmente no necesitan el generador premium.
 */
public enum SceneRole {
    GANCHO(30),
    EXPLICACION(10),
    CONTEXTO(5),
    GIRO(30),
    CONSECUENCIA(25),
    CIERRE(15);

    private final int narrativeWeight;

    SceneRole(int narrativeWeight) {
        this.narrativeWeight = narrativeWeight;
    }

    /** Peso narrativo en una escala 0-30, usado como insumo del scoring de dificultad. */
    public int narrativeWeight() {
        return narrativeWeight;
    }
}
