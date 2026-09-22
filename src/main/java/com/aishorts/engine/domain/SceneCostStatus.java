package com.aishorts.engine.domain;

/**
 * Estado de aprobación del COSTO de una escena (segunda puerta humana).
 * Solo existe una vez que el prompt ya fue aprobado.
 */
public enum SceneCostStatus {
    NOT_ESTIMATED,
    ESTIMATED,
    APPROVED,
    REJECTED
}
