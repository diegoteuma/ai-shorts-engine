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
        assertThatThrownBy(() -> ActualDurationChecker.Limits.ofSeconds(3, 8, 35, 40, 9, 3.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActualDurationChecker.Limits.ofSeconds(3, 8, 35, 40, 3.5, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- GANCHO y ritmo (incremento "gancho fiable") ---------------------------------------

    /** Los defaults de application.yml: escena 3-8 s, GANCHO desde 3,5 s, total 35-40 s, máximo 3,1 palabras/s. */
    private final ActualDurationChecker full = new ActualDurationChecker(
            ActualDurationChecker.Limits.ofSeconds(3, 8, 35, 40, 3.5, 3.1));

    private static SceneDuration hook(long millis, int words) {
        return new SceneDuration("v-gancho", Duration.ofMillis(millis), true, words);
    }

    private static SceneDuration scene(String id, long millis, int words) {
        return new SceneDuration(id, Duration.ofMillis(millis), false, words);
    }

    /** Solo los avisos de escena: con una sola escena el total (< 35 s) siempre avisa, y acá no interesa. */
    private List<Warning> sceneOnly(List<SceneDuration> scenes) {
        return sceneOnly(full, scenes);
    }

    private static List<Warning> sceneOnly(ActualDurationChecker c, List<SceneDuration> scenes) {
        return c.check(scenes).stream().filter(w -> w.sceneId() != null).toList();
    }

    @Test
    void valdiviaHook_isTooShortForAHook_andTooFast() {
        // valdivia-1960: GANCHO de 2,97 s con 12 palabras = 4,04 palabras/s.
        assertThat(sceneOnly(List.of(hook(2970, 12)))).containsExactly(
                new Warning("v-gancho", 2.97, 3.5, Kind.SCENE_TOO_SHORT, 12, 4.04),
                new Warning("v-gancho", 2.97, 3.871, Kind.NARRATION_TOO_FAST, 12, 4.04));
    }

    @Test
    void theHookMinimumIsInclusive_andOnlyAppliesToTheHook() {
        // 3,5 s exactos no avisa; 3,499 s sí (con 9 palabras: 2,57 palabras/s, sin aviso de ritmo).
        assertThat(sceneOnly(List.of(hook(3500, 9)))).isEmpty();
        assertThat(sceneOnly(List.of(hook(3499, 9))))
                .extracting(Warning::kind).containsExactly(Kind.SCENE_TOO_SHORT);
        // Otra escena de 3,2 s está dentro de su rango (mínimo 3 s).
        assertThat(sceneOnly(List.of(scene("v-cierre", 3200, 8)))).isEmpty();
    }

    @Test
    void narrationTooFast_aboveTheRate_notAtIt() {
        // 31 palabras en 10 s = 3,1 exacto: no avisa (inclusivo). 6 s con 19 palabras = 3,167: avisa.
        assertThat(sceneOnly(List.of(scene("a", 10_000, 31)))).extracting(Warning::kind).containsOnly(Kind.SCENE_TOO_LONG);
        List<Warning> fast = sceneOnly(List.of(scene("b", 6000, 19)));
        assertThat(fast).containsExactly(new Warning("b", 6.0, 6.129, Kind.NARRATION_TOO_FAST, 19, 3.167));
        // 2,65 palabras/s (la calibración): sin aviso.
        assertThat(sceneOnly(List.of(scene("c", 6000, 16)))).isEmpty();
    }

    @Test
    void withoutKnownWords_thereIsNoRateWarning_andTheOldLimitsBehaveAsBefore() {
        // Palabras desconocidas (constructor de 2 argumentos): solo el rango de duración.
        assertThat(sceneOnly(List.of(new SceneDuration("a", Duration.ofMillis(2000))))).containsExactly(
                new Warning("a", 2.0, 3.0, Kind.SCENE_TOO_SHORT));
        // Límites de 4 valores: el GANCHO usa el mínimo general y no hay aviso de ritmo.
        assertThat(sceneOnly(checker, List.of(hook(3200, 12)))).isEmpty();
    }

    @Test
    void totalOutOfRange_isReportedAsTooLongOrTooShort() {
        List<SceneDuration> six = List.of(hook(4000, 10), scene("b", 7000, 18), scene("c", 7000, 18),
                scene("d", 6000, 15), scene("e", 7000, 18), scene("f", 4000, 10));
        assertThat(full.check(six)).isEmpty(); // 35 s exactos
        List<SceneDuration> shortTotal = List.of(hook(4000, 10), scene("b", 6000, 15), scene("c", 6000, 15),
                scene("d", 6000, 15), scene("e", 6000, 15), scene("f", 4000, 10));
        assertThat(full.check(shortTotal)).containsExactly(new Warning(null, 32.0, 35.0, Kind.TOTAL_TOO_SHORT));
    }
}
