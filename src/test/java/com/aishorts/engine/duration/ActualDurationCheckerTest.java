package com.aishorts.engine.duration;

import com.aishorts.engine.duration.ActualDurationChecker.Kind;
import com.aishorts.engine.duration.ActualDurationChecker.SceneDuration;
import com.aishorts.engine.duration.ActualDurationChecker.Warning;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActualDurationCheckerTest {

    /** Los defaults de application.yml (engine.duration-warnings). */
    private final ActualDurationChecker checker = new ActualDurationChecker(ActualDurationChecker.Limits.ofSeconds(3, 8, 35, 40));

    /** Duraciones reales del audio de carrington-1859 (targetDuration en data/stories). */
    private static List<SceneDuration> carrington() {
        return new ArrayList<>(List.of(
                scene("carrington-1859-gancho", 5_016),
                scene("carrington-1859-explicacion", 7_988),
                scene("carrington-1859-contexto", 8_731),
                scene("carrington-1859-giro", 5_526),
                scene("carrington-1859-consecuencia", 9_799),
                scene("carrington-1859-cierre", 4_644)));
    }

    private static SceneDuration scene(String id, long millis) {
        return new SceneDuration(id, Duration.ofMillis(millis));
    }

    @Test
    void carrington_warnsContextoConsecuenciaAndTotal_notTheOthers() {
        assertThat(checker.check(carrington())).containsExactly(
                new Warning("carrington-1859-contexto", 8.731, 8.0, Kind.SCENE_TOO_LONG),
                new Warning("carrington-1859-consecuencia", 9.799, 8.0, Kind.SCENE_TOO_LONG),
                new Warning(null, 41.704, 40.0, Kind.TOTAL_TOO_LONG));
    }

    /** La escena "a" con esa duración más cuatro de 7 s (con a = 8 s, el total es 36 s: dentro de 35-40). */
    private static List<SceneDuration> withSceneA(long millis) {
        return List.of(scene("a", millis), scene("b", 7_000), scene("c", 7_000), scene("d", 7_000), scene("e", 7_000));
    }

    @Test
    void exactlyTheMaximumDoesNotWarn_oneMillisecondMoreDoes() {
        assertThat(checker.check(withSceneA(8_000))).isEmpty();
        assertThat(checker.check(withSceneA(8_001)))
                .containsExactly(new Warning("a", 8.001, 8.0, Kind.SCENE_TOO_LONG));
    }

    @Test
    void exactlyTheMinimumDoesNotWarn_oneMillisecondLessDoes() {
        List<SceneDuration> scenes = new ArrayList<>(withSceneA(3_000));
        scenes.add(scene("f", 7_000)); // 38 s en total
        assertThat(checker.check(scenes)).isEmpty();
        scenes.set(0, scene("a", 2_999));
        assertThat(checker.check(scenes))
                .containsExactly(new Warning("a", 2.999, 3.0, Kind.SCENE_TOO_SHORT));
    }

    @Test
    void totalLimitsAreInclusiveToo() {
        List<SceneDuration> forty = List.of(scene("a", 8_000), scene("b", 8_000), scene("c", 8_000),
                scene("d", 8_000), scene("e", 8_000));
        assertThat(checker.check(forty)).isEmpty();
        List<SceneDuration> thirtyFive = List.of(scene("a", 7_000), scene("b", 7_000), scene("c", 7_000),
                scene("d", 7_000), scene("e", 7_000));
        assertThat(checker.check(thirtyFive)).isEmpty();
        List<SceneDuration> tooShort = List.of(scene("a", 7_000), scene("b", 7_000), scene("c", 7_000),
                scene("d", 7_000), scene("e", 6_999));
        assertThat(checker.check(tooShort)).containsExactly(new Warning(null, 34.999, 35.0, Kind.TOTAL_TOO_SHORT));
    }

    @Test
    void scenesWithoutDurationAreIgnored_andThenTheTotalIsNotEvaluated() {
        List<SceneDuration> scenes = carrington();
        scenes.set(4, new SceneDuration("carrington-1859-consecuencia", null));
        // la suma parcial (31.905 s) quedaría por debajo de 35: no se avisa porque no es el total del video
        assertThat(checker.check(scenes)).containsExactly(
                new Warning("carrington-1859-contexto", 8.731, 8.0, Kind.SCENE_TOO_LONG));
    }

    @Test
    void noScenes_orNoneMeasured_noWarnings() {
        assertThat(checker.check(List.of())).isEmpty();
        assertThat(checker.check(List.of(new SceneDuration("a", null), new SceneDuration("b", null)))).isEmpty();
    }

    @Test
    void invalidLimitsFailFast() {
        assertThatThrownBy(() -> ActualDurationChecker.Limits.ofSeconds(8, 3, 35, 40))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActualDurationChecker.Limits.ofSeconds(3, 8, 40, 35))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
