package com.aishorts.engine.demo;

import com.aishorts.engine.higgsfield.EstimateRequest;
import com.aishorts.engine.higgsfield.EstimateResponse;
import com.aishorts.engine.higgsfield.GenerationRequest;
import com.aishorts.engine.higgsfield.GenerationResponse;
import com.aishorts.engine.higgsfield.GenerationStatusResponse;
import com.aishorts.engine.higgsfield.HiggsfieldClient;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implementación falsa de HiggsfieldClient para poder correr el flujo
 * completo sin llamar a la red real. Útil para el demo y para tests futuros
 * de StoryApprovalService.
 */
final class FakeHiggsfieldClient implements HiggsfieldClient {

    private final AtomicInteger requestCounter = new AtomicInteger(1);

    @Override
    public EstimateResponse estimateCost(EstimateRequest request) {
        boolean premium = request.modelId().contains("premium");
        BigDecimal cost = premium ? new BigDecimal("1.20") : new BigDecimal("0.35");
        return new EstimateResponse(cost, "USD", request.modelId(), Map.of(
                "cost", cost,
                "currency", "USD",
                "model_id", request.modelId()
        ));
    }

    @Override
    public GenerationResponse submitGeneration(GenerationRequest request) {
        String id = "fake-req-" + requestCounter.getAndIncrement();
        return new GenerationResponse(id, "https://fake.higgsfield.local/status/" + id, "queued");
    }

    @Override
    public GenerationStatusResponse pollStatus(String statusUrl) {
        String id = statusUrl.substring(statusUrl.lastIndexOf('/') + 1);
        return new GenerationStatusResponse(id, "completed", "https://fake.higgsfield.local/output/" + id + ".mp4", null);
    }
}
