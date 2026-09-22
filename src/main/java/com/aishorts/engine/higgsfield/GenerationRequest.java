package com.aishorts.engine.higgsfield;

import java.util.Map;

public record GenerationRequest(String modelId, Map<String, Object> parameters) {
}
