package com.aishorts.engine.video;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuración del video final (engine.final-video.* en application.yml,
 * cada valor con su variable de entorno y su default). Los timeouts y el
 * mínimo de disco son valores iniciales SIN MEDIR en la VM (salvo el render:
 * 1014 s medidos para tunguska), ver application.yml.
 */
@ConfigurationProperties(prefix = "engine.final-video")
public record FinalVideoProperties(
        boolean enabled,
        String rendersDir,
        String rendererDir,
        String nodeCommand,
        long minFreeBytes,
        boolean keepWorkDir,
        Timeouts timeouts
) {

    /** En segundos. transcribeScene es por escena; el resto, por etapa. */
    public record Timeouts(
            long transcribeSceneSeconds,
            long generateSeconds,
            long lintSeconds,
            long checkSeconds,
            long renderSeconds
    ) {
    }

    /** Margen para preparar, validar y arrancar Node/npx, encima de la suma de etapas. */
    static final Duration PROCESS_MARGIN = Duration.ofMinutes(5);

    /** Tope para todo el proceso de Node: la suma de las etapas más un margen (Node ya corta cada etapa por su cuenta). */
    Duration totalTimeout(int sceneCount) {
        long seconds = timeouts.transcribeSceneSeconds() * sceneCount + timeouts.generateSeconds()
                + timeouts.lintSeconds() + timeouts.checkSeconds() + timeouts.renderSeconds();
        return Duration.ofSeconds(seconds).plus(PROCESS_MARGIN);
    }
}
