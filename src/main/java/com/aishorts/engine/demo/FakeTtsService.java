package com.aishorts.engine.demo;

import com.aishorts.engine.duration.NarrationDurationEstimator;
import com.aishorts.engine.tts.TtsResult;
import com.aishorts.engine.tts.TtsService;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Simula ElevenLabs: parte de la misma estimación por palabras que ya
 * usamos, pero le suma un desfasaje fijo para representar que el audio real
 * nunca coincide exacto con la estimación (pausas, énfasis, etc.) — así el
 * demo muestra que la duración se refina, no que se repite el mismo número.
 */
final class FakeTtsService implements TtsService {

    private final NarrationDurationEstimator baseEstimator;

    FakeTtsService(NarrationDurationEstimator baseEstimator) {
        this.baseEstimator = baseEstimator;
    }

    @Override
    public TtsResult synthesize(String narrationText) {
        Duration estimated = baseEstimator.estimate(narrationText);
        Duration real = estimated.plusMillis(300);
        byte[] fakeAudio = ("FAKE_MP3_AUDIO:" + narrationText).getBytes(StandardCharsets.UTF_8);
        return new TtsResult(fakeAudio, real);
    }
}
