package com.aishorts.engine.difficulty;

/**
 * Señales de complejidad visual de una escena, cada una en escala 0-10.
 * Estas las completa quien arma el guion/storyboard (o un paso de análisis
 * automático más adelante); el scorer las combina con el peso narrativo del
 * rol de la escena para sugerir el generador.
 *
 * - movementComplexity: cuánta dinámica de cámara/movimiento de objetos hay
 *   (una toma estática del bosque = bajo; una onda expansiva propagándose = alto).
 * - elementCount: cantidad de elementos distintos que deben verse coherentes
 *   a la vez (un objeto solo = bajo; una ciudad con edificios, calles y daño
 *   estructural = alto).
 * - physicalRealism: cuánto exige la escena que la física/luz se vea creíble
 *   (un mapa ilustrado = bajo; una recreación fotorrealista de destrucción = alto).
 * - cameraDynamism: cuánto la cámara cambia de plano, hace zoom o sigue acción.
 */
public record DifficultyFactors(
        int movementComplexity,
        int elementCount,
        int physicalRealism,
        int cameraDynamism
) {
    public DifficultyFactors {
        requireRange("movementComplexity", movementComplexity);
        requireRange("elementCount", elementCount);
        requireRange("physicalRealism", physicalRealism);
        requireRange("cameraDynamism", cameraDynamism);
    }

    private static void requireRange(String name, int value) {
        if (value < 0 || value > 10) {
            throw new IllegalArgumentException(name + " debe estar entre 0 y 10, recibido: " + value);
        }
    }

    /** Suma cruda de los cuatro factores, máximo 40. */
    public int rawSum() {
        return movementComplexity + elementCount + physicalRealism + cameraDynamism;
    }
}
