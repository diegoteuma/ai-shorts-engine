package com.aishorts.engine.domain;

import java.util.regex.Pattern;

/**
 * Regla única para el id de una historia: termina en nombres de archivo
 * (DATA_DIR/{id}.json, y vía los ids de escena {id}-{rol}, AUDIO_DIR/{idEscena}.mp3,
 * CLIPS_DIR/{idEscena}.mp4, RENDERS_DIR/{id}/). Se rechaza, nunca se "arregla":
 * sustituir caracteres hacía que "a.b" y "a_b" compartieran archivo.
 *
 * Largo máximo 64: cubre el UUID que genera POST /stories sin storyId (36) y
 * los slugs del generador de historias (DraftText.MAX_ID_LENGTH = 40), y deja
 * el nombre de archivo más largo ({id}-consecuencia.mp3, 81 caracteres) muy
 * por debajo del límite de 255 de los sistemas de archivos habituales.
 */
public final class StoryIds {

    public static final int MAX_LENGTH = 64;
    private static final Pattern PATTERN = Pattern.compile("[A-Za-z0-9_-]+");

    private StoryIds() {
    }

    public static boolean isValid(String id) {
        return id != null && id.length() <= MAX_LENGTH && PATTERN.matcher(id).matches();
    }

    /** @throws IllegalArgumentException con un mensaje que dice qué se acepta */
    public static String requireValid(String id) {
        if (!isValid(id)) {
            throw new IllegalArgumentException("Id de historia no permitido: '" + id + "'. Solo letras, números, '_' y '-'"
                    + " ([A-Za-z0-9_-]+), hasta " + MAX_LENGTH + " caracteres.");
        }
        return id;
    }
}
