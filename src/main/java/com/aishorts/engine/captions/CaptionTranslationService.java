package com.aishorts.engine.captions;

import java.util.List;

/**
 * Traduce las líneas de narración (español) a inglés para la pista de
 * subtítulos en inglés. No re-narra ni resume — el audio del video sigue
 * siendo el español original; esto es solo para el texto que se lee.
 */
public interface CaptionTranslationService {

    /** Devuelve las traducciones en el mismo orden y con la misma cantidad de elementos que narrationTextsEs. */
    List<String> translateToEnglish(List<String> narrationTextsEs) throws CaptionTranslationException;
}
