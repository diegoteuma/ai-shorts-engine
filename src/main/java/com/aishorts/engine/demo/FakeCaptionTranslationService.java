package com.aishorts.engine.demo;

import com.aishorts.engine.captions.CaptionTranslationService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Traducciones fijas para el guion de Tunguska, simulando lo que devolvería Claude. */
final class FakeCaptionTranslationService implements CaptionTranslationService {

    private static final Map<String, String> KNOWN_TRANSLATIONS = new LinkedHashMap<>();
    static {
        KNOWN_TRANSLATIONS.put(
                "Explotó en el cielo. No dejó cráter.",
                "It exploded in the sky. It left no crater.");
        KNOWN_TRANSLATIONS.put(
                "El objeto nunca llegó al suelo. Se desintegró en el aire, y la onda de la explosión arrasó el bosque en cuestión de segundos.",
                "The object never reached the ground. It disintegrated in the air, and the blast wave flattened the forest in seconds.");
        KNOWN_TRANSLATIONS.put(
                "30 de junio de 1908, Siberia. Ochenta millones de árboles derribados en más de 2.000 kilómetros cuadrados. Esto es Tunguska.",
                "June 30, 1908, Siberia. Eighty million trees knocked down over more than 2,000 square kilometers. This is Tunguska.");
        KNOWN_TRANSLATIONS.put(
                "Ahora cambia una sola cosa. Pon esa misma explosión sobre una ciudad.",
                "Now change just one thing. Put that same explosion over a city.");
        KNOWN_TRANSLATIONS.put(
                "La onda expansiva se propaga en todas direcciones. El daño depende de la altura de la explosión, su energía y lo que haya debajo.",
                "The blast wave spreads in every direction. The damage depends on the height of the explosion, its energy, and what's underneath.");
        KNOWN_TRANSLATIONS.put(
                "¿Qué pasaría si una explosión así ocurriera hoy, sobre una ciudad y no sobre un bosque vacío?",
                "What would happen if an explosion like this happened today, over a city instead of an empty forest?");
    }

    @Override
    public List<String> translateToEnglish(List<String> narrationTextsEs) {
        return narrationTextsEs.stream()
                .map(text -> KNOWN_TRANSLATIONS.getOrDefault(text, "[traducción no disponible en el fake: " + text + "]"))
                .toList();
    }
}
