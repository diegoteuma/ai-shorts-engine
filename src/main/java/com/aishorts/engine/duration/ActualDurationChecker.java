package com.aishorts.engine.duration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Avisos (nunca bloqueos) sobre la duración REAL de la narración, la que
 * devuelve el TTS. Sirve para ver, antes de aprobar costos en la PUERTA 2,
 * una escena o un total fuera de las reglas, cuando todavía es barato
 * acortar el texto. Función pura: sin Spring ni I/O.
 *
 * Las escenas con duración null se ignoran (sin aviso). El total solo se
 * evalúa si TODAS las escenas tienen duración: con una parte sin medir, la
 * suma no es el total del video y avisaría de más (o de menos).
 *
 * Además del rango por escena, el GANCHO tiene su propio mínimo
 * (minHookScene: un gancho de menos de ~3,5 s no alcanza a mostrar el
 * evento) y cada escena con palabras conocidas se compara contra un ritmo
 * máximo (maxWordsPerSecond): un ritmo muy por encima de la calibración
 * (2,65 palabras/s) indica que la voz se aceleró o que el texto no entra.
 */
public final class ActualDurationChecker {

    /**
     * Límites inclusivos: exactamente el máximo (o el mínimo) no avisa.
     * maxWordsPerSecond infinito = sin aviso de ritmo.
     */
    public record Limits(Duration minScene, Duration maxScene, Duration minTotal, Duration maxTotal,
                         Duration minHookScene, double maxWordsPerSecond) {
        public Limits {
            Objects.requireNonNull(minScene, "minScene");
            Objects.requireNonNull(maxScene, "maxScene");
            Objects.requireNonNull(minTotal, "minTotal");
            Objects.requireNonNull(maxTotal, "maxTotal");
            Objects.requireNonNull(minHookScene, "minHookScene");
            if (minScene.isNegative() || maxScene.compareTo(minScene) < 0 || minTotal.isNegative() || maxTotal.compareTo(minTotal) < 0
                    || minHookScene.isNegative() || maxScene.compareTo(minHookScene) < 0) {
                throw new IllegalArgumentException("Límites de duración inválidos: escena " + minScene + "-" + maxScene
                        + ", gancho desde " + minHookScene + ", total " + minTotal + "-" + maxTotal);
            }
            if (Double.isNaN(maxWordsPerSecond) || maxWordsPerSecond <= 0) {
                throw new IllegalArgumentException("maxWordsPerSecond debe ser > 0, llegó: " + maxWordsPerSecond);
            }
        }

        /** Sin mínimo propio del GANCHO ni aviso de ritmo (el comportamiento anterior). */
        public Limits(Duration minScene, Duration maxScene, Duration minTotal, Duration maxTotal) {
            this(minScene, maxScene, minTotal, maxTotal, minScene, Double.POSITIVE_INFINITY);
        }

        public static Limits ofSeconds(double minScene, double maxScene, double minTotal, double maxTotal) {
            return new Limits(seconds(minScene), seconds(maxScene), seconds(minTotal), seconds(maxTotal));
        }

        public static Limits ofSeconds(double minScene, double maxScene, double minTotal, double maxTotal,
                                       double minHookScene, double maxWordsPerSecond) {
            return new Limits(seconds(minScene), seconds(maxScene), seconds(minTotal), seconds(maxTotal),
                    seconds(minHookScene), maxWordsPerSecond);
        }

        private static Duration seconds(double value) {
            return Duration.ofMillis(Math.round(value * 1000));
        }
    }

    /**
     * La duración real de una escena, o null si todavía no tiene audio.
     * hook: es el GANCHO (usa minHookScene). words: palabras de la
     * narración contadas como el validador del borrador, o -1 si no se
     * conocen (sin aviso de ritmo).
     */
    public record SceneDuration(String sceneId, Duration actual, boolean hook, int words) {
        public SceneDuration(String sceneId, Duration actual) {
            this(sceneId, actual, false, -1);
        }
    }

    public enum Kind {
        SCENE_TOO_LONG,
        SCENE_TOO_SHORT,
        /** Más palabras por segundo que maxWordsPerSecond. */
        NARRATION_TOO_FAST,
        TOTAL_TOO_LONG,
        TOTAL_TOO_SHORT
    }

    /**
     * sceneId es null en los avisos del total. Los segundos van con la
     * precisión del audio (milisegundos). En NARRATION_TOO_FAST,
     * limitSeconds es lo mínimo que debería durar ese texto al ritmo máximo
     * (palabras / maxWordsPerSecond). words y wordsPerSecond se informan en
     * los avisos de escena cuando se conocen las palabras; null si no.
     */
    public record Warning(String sceneId, double actualSeconds, double limitSeconds, Kind kind,
                          Integer words, Double wordsPerSecond) {
        public Warning(String sceneId, double actualSeconds, double limitSeconds, Kind kind) {
            this(sceneId, actualSeconds, limitSeconds, kind, null, null);
        }
    }

    private final Limits limits;

    public ActualDurationChecker(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public List<Warning> check(List<SceneDuration> scenes) {
        List<Warning> warnings = new ArrayList<>();
        Duration total = Duration.ZERO;
        boolean allMeasured = !scenes.isEmpty();
        for (SceneDuration scene : scenes) {
            if (scene == null || scene.actual() == null) {
                allMeasured = false;
                continue;
            }
            Duration actual = scene.actual();
            total = total.plus(actual);
            Integer words = scene.words() >= 0 ? scene.words() : null;
            Double rate = words != null && actual.toMillis() > 0 ? round3(words / (actual.toMillis() / 1000.0)) : null;
            Duration minScene = scene.hook() ? limits.minHookScene() : limits.minScene();
            if (actual.compareTo(limits.maxScene()) > 0) {
                warnings.add(warning(scene.sceneId(), actual, limits.maxScene(), Kind.SCENE_TOO_LONG, words, rate));
            } else if (actual.compareTo(minScene) < 0) {
                warnings.add(warning(scene.sceneId(), actual, minScene, Kind.SCENE_TOO_SHORT, words, rate));
            }
            if (rate != null && words / (actual.toMillis() / 1000.0) > limits.maxWordsPerSecond()) {
                Duration needed = Duration.ofMillis(Math.round(words / limits.maxWordsPerSecond() * 1000));
                warnings.add(warning(scene.sceneId(), actual, needed, Kind.NARRATION_TOO_FAST, words, rate));
            }
        }
        if (allMeasured) {
            if (total.compareTo(limits.maxTotal()) > 0) {
                warnings.add(warning(null, total, limits.maxTotal(), Kind.TOTAL_TOO_LONG, null, null));
            } else if (total.compareTo(limits.minTotal()) < 0) {
                warnings.add(warning(null, total, limits.minTotal(), Kind.TOTAL_TOO_SHORT, null, null));
            }
        }
        return warnings;
    }

    private static Warning warning(String sceneId, Duration actual, Duration limit, Kind kind, Integer words, Double rate) {
        return new Warning(sceneId, actual.toMillis() / 1000.0, limit.toMillis() / 1000.0, kind, words, rate);
    }

    private static double round3(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}
