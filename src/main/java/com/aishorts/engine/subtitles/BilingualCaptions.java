package com.aishorts.engine.subtitles;

/**
 * srtEs: también es la fuente para quemar los subtítulos en el video (el
 * audio narrado es español, así que el quemado tiene que coincidir con lo
 * que se escucha). srtEn: solo se sube como pista de closed captions, no se
 * quema — quemar dos idiomas implicaría renderizar el video dos veces.
 */
public record BilingualCaptions(String srtEs, String srtEn) {
}
