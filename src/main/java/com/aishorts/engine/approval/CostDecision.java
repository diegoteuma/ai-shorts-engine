package com.aishorts.engine.approval;

public record CostDecision(String sceneId, Decision decision, String note) {
    public static CostDecision approve(String sceneId) {
        return new CostDecision(sceneId, Decision.APPROVE, null);
    }

    public static CostDecision reject(String sceneId, String note) {
        return new CostDecision(sceneId, Decision.REJECT, note);
    }
}
