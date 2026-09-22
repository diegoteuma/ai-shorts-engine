package com.aishorts.engine.higgsfield;

/** outputUrl y failureReason son nuldeables según el status. */
public record GenerationStatusResponse(String requestId, String status, String outputUrl, String failureReason) {
}
