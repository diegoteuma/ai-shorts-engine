package com.aishorts.engine.tts;

import java.time.Duration;

/**
 * duration viene del alignment por carácter que devuelve el proveedor (la
 * suma real del audio sintetizado), no de una estimación por conteo de
 * palabras — es la duración de verdad que va a tener esa escena en el video
 * final.
 */
public record TtsResult(byte[] audioBytes, Duration duration) {
}
