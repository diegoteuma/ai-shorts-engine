package com.aishorts.engine.drafts;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Utilidades de texto puras del generador: conteo de palabras, normalización de temas, slugs e ids. */
public final class DraftText {

    /** Formato de id de draft/story aceptado por la API (también evita path traversal al armar el nombre de archivo). */
    public static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    public static final int MAX_ID_LENGTH = 40;

    private DraftText() {
    }

    public static boolean isValidId(String id) {
        return id != null && id.length() <= MAX_ID_LENGTH && ID_PATTERN.matcher(id).matches();
    }

    /** Mismo criterio que WordsPerSecondDurationEstimator: tokens separados por espacios. */
    public static int countWords(String text) {
        if (text == null) {
            return 0;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
    }

    /** Minúsculas, sin tildes, solo letras/dígitos separados por un espacio. Para comparar temas y títulos. */
    public static String normalizeTopic(String text) {
        if (text == null) {
            return "";
        }
        String noAccents = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return noAccents.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    /**
     * Dos temas "se repiten" si, normalizados, son iguales o uno contiene al
     * otro como frase completa (de al menos 5 caracteres, para que una
     * palabra suelta y corta no coincida con todo).
     */
    public static boolean topicsOverlap(String a, String b) {
        String na = normalizeTopic(a);
        String nb = normalizeTopic(b);
        if (na.isEmpty() || nb.isEmpty()) {
            return false;
        }
        if (na.equals(nb)) {
            return true;
        }
        String shorter = na.length() <= nb.length() ? na : nb;
        String longer = na.length() <= nb.length() ? nb : na;
        return shorter.length() >= 5 && (" " + longer + " ").contains(" " + shorter + " ");
    }

    /** Slug válido (ver ID_PATTERN) a partir de un texto libre; "historia" si no queda nada. */
    public static String slugify(String text) {
        String slug = normalizeTopic(text).replace(' ', '-');
        if (slug.length() > MAX_ID_LENGTH) {
            slug = slug.substring(0, MAX_ID_LENGTH);
            int lastDash = slug.lastIndexOf('-');
            if (lastDash > 0) {
                slug = slug.substring(0, lastDash);
            }
        }
        slug = slug.replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "historia" : slug;
    }
}
