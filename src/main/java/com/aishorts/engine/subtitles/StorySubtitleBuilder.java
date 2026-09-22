package com.aishorts.engine.subtitles;

import com.aishorts.engine.captions.CaptionTranslationService;
import com.aishorts.engine.domain.Scene;
import com.aishorts.engine.domain.Story;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Arma las dos pistas .srt de una historia (español e inglés) con
 * exactamente el mismo timing por escena — el que ya calculó
 * NarrationDurationEstimator para cada Scene — para que ambas queden
 * sincronizadas con el mismo video sin importar en qué idioma se estén
 * leyendo.
 *
 * Llamar esto recién cuando todas las narraciones ya están en su versión
 * final (después de la puerta 1): traducir antes de tiempo significaría
 * volver a traducir cada vez que se revisa una narración.
 */
public final class StorySubtitleBuilder {

    private final CaptionTranslationService translationService;

    public StorySubtitleBuilder(CaptionTranslationService translationService) {
        this.translationService = translationService;
    }

    public BilingualCaptions build(Story story) {
        List<String> narrationTexts = story.scenes().stream().map(Scene::narrationText).toList();
        List<String> englishTexts = translationService.translateToEnglish(narrationTexts);

        List<SubtitleCue> esCues = new ArrayList<>();
        List<SubtitleCue> enCues = new ArrayList<>();
        Duration cursor = Duration.ZERO;
        int i = 0;
        for (Scene scene : story.scenes()) {
            Duration end = cursor.plus(scene.targetDuration());
            esCues.add(new SubtitleCue(cursor, end, scene.narrationText()));
            enCues.add(new SubtitleCue(cursor, end, englishTexts.get(i)));
            cursor = end;
            i++;
        }
        return new BilingualCaptions(SrtGenerator.toSrt(esCues), SrtGenerator.toSrt(enCues));
    }
}
