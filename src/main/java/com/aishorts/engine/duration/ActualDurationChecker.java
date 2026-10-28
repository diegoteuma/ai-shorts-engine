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
 */
public final class ActualDurationChecker {

    /** Límites inclusivos: exactamente el máximo (o el mínimo) no avisa. */
    public record Limits(Duration minScene, Duration maxScene, Duration minTotal, Duration maxTotal) {
        public Limits {
            Objects.requireNonNull(minScene, "minScene");
            Objects.requireNonNull(maxScene, "maxScene");
            Objects.requireNonNull(minTotal, "minTotal");
            Objects.requireNonNull(maxTotal, "maxTotal");
            if (minScene.isNegative() || maxScene.compareTo(minScene) < 0 || minTotal.isNegative() || maxTotal.compareTo(minTotal) < 0) {
                throw new IllegalArgumentException("Límites de duración inválidos: escena " + minScene + "-" + maxScene
                        + ", total " + minTotal + "-" + maxTotal);
            }
        }

        public static Limits ofSeconds(double minScene, double maxScene, double minTotal, double maxTotal) {
            return new Limits(seconds(minScene), seconds(maxScene), seconds(minTotal), seconds(maxTotal));
        }

        private static Duration seconds(double value) {
            return Duration.ofMillis(Math.round(value * 1000));
        }
    }

    /** La duración real de una escena, o null si todavía no tiene audio. */
    public record SceneDuration(String sceneId, Duration actual) {
    }

    public enum Kind {
        SCENE_TOO_LONG,
        SCENE_TOO_SHORT,
        TOTAL_TOO_LONG,
        TOTAL_TOO_SHORT
    }

    /** sceneId es null en los avisos del total. Los segundos van con la precisión del audio (milisegundos). */
    public record Warning(String sceneId, double actualSeconds, double limitSeconds, Kind kind) {
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
            if (actual.compareTo(limits.maxScene()) > 0) {
                warnings.add(warning(scene.sceneId(), actual, limits.maxScene(), Kind.SCENE_TOO_LONG));
            } else if (actual.compareTo(limits.minScene()) < 0) {
                warnings.add(warning(scene.sceneId(), actual, limits.minScene(), Kind.SCENE_TOO_SHORT));
            }
        }
        if (allMeasured) {
            if (total.compareTo(limits.maxTotal()) > 0) {
                warnings.add(warning(null, total, limits.maxTotal(), Kind.TOTAL_TOO_LONG));
            } else if (total.compareTo(limits.minTotal()) < 0) {
                warnings.add(warning(null, total, limits.minTotal(), Kind.TOTAL_TOO_SHORT));
            }
        }
        return warnings;
    }

    private static Warning warning(String sceneId, Duration actual, Duration limit, Kind kind) {
        return new Warning(sceneId, actual.toMillis() / 1000.0, limit.toMillis() / 1000.0, kind);
    }
}
