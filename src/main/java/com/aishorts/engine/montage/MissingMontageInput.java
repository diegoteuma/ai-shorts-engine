package com.aishorts.engine.montage;

/**
 * Un archivo que el montaje necesita y no está en disco.
 *
 * @param sceneId      la escena a la que le falta
 * @param kind         "clip" (video de la escena) o "audio" (narración)
 * @param expectedPath ruta absoluta donde se espera el archivo
 * @param description  línea legible para el mensaje de error
 */
public record MissingMontageInput(String sceneId, String kind, String expectedPath, String description) {
}
