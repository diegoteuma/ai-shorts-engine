package com.aishorts.engine.tts;

/**
 * Sintetiza voz a partir del texto de narración ya aprobado. Se llama
 * DESPUÉS de la puerta 1 (prompt aprobado) — es contenido pago, aunque sea
 * de centavos, así que no se dispara sobre texto que todavía podés rechazar.
 */
public interface TtsService {
    TtsResult synthesize(String narrationText) throws TtsException;
}
