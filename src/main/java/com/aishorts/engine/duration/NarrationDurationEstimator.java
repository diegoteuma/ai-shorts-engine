package com.aishorts.engine.duration;

import java.time.Duration;

/**
 * Estima cuánto dura hablada una narración. Es una aproximación por conteo
 * de palabras — el número real lo va a dar el audio de TTS una vez que se
 * elija ese proveedor; hasta entonces, esta estimación es la que se usa para
 * validar el presupuesto de tiempo del Short en la puerta 1, antes de gastar
 * nada en generación visual ni en síntesis de voz.
 */
public interface NarrationDurationEstimator {
    Duration estimate(String narrationText);
}
