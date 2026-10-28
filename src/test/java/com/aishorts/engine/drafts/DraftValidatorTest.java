package com.aishorts.engine.drafts;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.aishorts.engine.drafts.DraftFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Una prueba por regla: se parte del borrador de oro (cero violaciones), se
 * rompe UNA cosa y se verifica que aparece exactamente esa violación con su
 * severidad. Sin Spring ni red: normalizador + validador puros, con la
 * configuración real de application.yml.
 */
class DraftValidatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);

    private final DraftsProperties properties = DraftFixtures.properties();
    private final SourcePolicy policy = SourcePolicy.from(properties);
    private final DraftNormalizer normalizer = new DraftNormalizer(properties, policy, new ObjectMapper());
    private final DraftValidator validator = new DraftValidator(properties, policy);

    private Map<String, Object> draft = validDraft("tunguska-x", "Estallido de Tunguska de 1908");
    private Set<String> takenIds = Set.of();
    private List<ExistingStory> existing = List.of();
    private Collection<String> researched = RESEARCHED;

    private StoryDraft lastDraft;

    private List<Violation> validate() {
        DraftNormalizer.Parsed parsed = normalizer.parse(json(draft));
        lastDraft = parsed.draft();
        List<Violation> violations = new ArrayList<>(parsed.violations());
        if (!parsed.jsonInvalid()) {
            normalizer.recalculate(lastDraft, researched, TODAY);
            violations.addAll(validator.validate(lastDraft, new DraftValidator.Context(takenIds, existing, researched)));
        }
        return violations;
    }

    private static List<String> codes(List<Violation> violations, Severity severity) {
        return violations.stream().filter(v -> v.severity() == severity).map(Violation::code).distinct().toList();
    }

    private void assertOnly(Severity severity, String code) {
        List<Violation> violations = validate();
        assertThat(codes(violations, severity)).as(violations.toString()).containsExactly(code);
    }

    private void assertHard(String code) {
        List<Violation> violations = validate();
        assertThat(codes(violations, Severity.HARD)).as(violations.toString()).contains(code);
    }

    // --- borrador de oro -----------------------------------------------------------------

    @Test
    void goldenDraft_hasNoViolationsAtAll() {
        assertThat(validate()).isEmpty();
    }

    @Test
    void serverRecalculatesWordsChecksTierAndAccessed_ignoringWhatTheModelSent() {
        validate();
        assertThat(lastDraft.scenes).extracting(s -> s.words).containsExactly(12, 19, 18, 15, 18, 12);
        assertThat(lastDraft.scenes.get(1).estimatedSeconds).isEqualTo(7.17);
        assertThat(lastDraft.checks.totalWords).isEqualTo(94);
        assertThat(lastDraft.checks.estimatedSeconds).isEqualTo(35.47);
        assertThat(lastDraft.checks.allFactsHaveClaims).isTrue();
        assertThat(lastDraft.checks.sourcesVerified).isTrue();
        StoryDraft.Claim c1 = DraftRules.claimsById(lastDraft).get("c1");
        assertThat(c1.sources).extracting(s -> s.tier).containsExactly("A", "B");
        assertThat(c1.sources).extracting(s -> s.accessed).containsOnly("2026-10-06");
        assertThat(c1.status).isEqualTo("VERIFICADO");
    }

    // --- HARD ---------------------------------------------------------------------------

    @Test
    void hard_jsonInvalid() {
        DraftNormalizer.Parsed parsed = normalizer.parse("Lo siento, no encontré nada {sin json");
        assertThat(codes(parsed.violations(), Severity.HARD)).containsExactly("JSON_INVALID");
        assertThat(parsed.draft().unparsedResponse).contains("Lo siento");
    }

    @Test
    void jsonIsExtractedFromFirstBraceToLastBrace_evenWithProseAndFenceAround() {
        DraftNormalizer.Parsed parsed = normalizer.parse("Aquí va:\n```json\n" + json(draft) + "\n```\nListo.");
        assertThat(parsed.violations()).isEmpty();
        assertThat(parsed.draft().id).isEqualTo("tunguska-x");
    }

    @Test
    void hard_sceneCount() {
        scenes(draft).remove(5);
        assertHard("SCENE_COUNT");
    }

    @Test
    void hard_sceneRoleOutOfOrder() {
        scene(draft, 0).put("role", "EXPLICACION");
        scene(draft, 1).put("role", "GANCHO");
        assertHard("SCENE_ROLE");
    }

    @Test
    void hard_unknownRole_isReportedAsWrongRole_notAsACrash() {
        scene(draft, 3).put("role", "INTRO");
        List<Violation> violations = validate();
        assertThat(codes(violations, Severity.HARD)).containsExactly("SCENE_ROLE");
    }

    @Test
    void hard_sceneOrderNumber() {
        scene(draft, 2).put("order", 7);
        assertOnly(Severity.HARD, "SCENE_ORDER");
    }

    @Test
    void hard_slugInvalid() {
        draft.put("id", "Tunguska_X");
        assertOnly(Severity.HARD, "SLUG_INVALID");
    }

    @Test
    void hard_slugTooLong() {
        draft.put("id", "a".repeat(41));
        assertHard("SLUG_INVALID");
    }

    @Test
    void hard_slugAlreadyTaken() {
        takenIds = Set.of("tunguska-x");
        assertOnly(Severity.HARD, "SLUG_TAKEN");
    }

    @Test
    void hard_sceneIdMustBeSlugDashRole() {
        scene(draft, 3).put("id", "otra-cosa-giro");
        assertOnly(Severity.HARD, "SCENE_ID");
    }

    @Test
    void hard_totalWordsBelowMinimum() {
        scene(draft, 5).put("narrationText", words(8)); // 94 - 4 = 90, la escena sigue en 3 s
        assertOnly(Severity.HARD, "TOTAL_WORDS");
    }

    @Test
    void hard_totalWordsAboveMaximum() {
        scene(draft, 1).put("narrationText", words(21));
        scene(draft, 2).put("narrationText", words(20));
        scene(draft, 4).put("narrationText", words(20));
        scene(draft, 3).put("narrationText", words(17));
        scene(draft, 0).put("narrationText", words(14)); // 14+21+20+17+20+12 = 104: todavía válido
        assertThat(codes(validate(), Severity.HARD)).isEmpty();
        scene(draft, 5).put("narrationText", words(13)); // 105
        assertHard("TOTAL_WORDS");
    }

    @Test
    void hard_sceneLongerThanMaxSeconds() {
        scene(draft, 0).put("narrationText", words(22)); // 22 / 2.65 = 8.3 s; total 104
        assertOnly(Severity.HARD, "SCENE_DURATION");
    }

    @Test
    void hard_sceneShorterThanMinSeconds() {
        scene(draft, 5).put("narrationText", words(7)); // 2.6 s
        assertHard("SCENE_DURATION");
    }

    @Test
    void hard_sceneReferencesMissingClaim() {
        scene(draft, 0).put("claimIds", List.of("c1", "c9"));
        assertOnly(Severity.HARD, "CLAIM_REF_MISSING");
    }

    @Test
    void hard_whatIfParameterReferencesMissingClaim() {
        @SuppressWarnings("unchecked")
        Map<String, Object> param = ((List<Map<String, Object>>) whatIf(draft).get("parametersFromReal")).get(0);
        param.put("claimId", "c42");
        assertOnly(Severity.HARD, "WHATIF_CLAIM_REF_MISSING");
    }

    @Test
    void hard_realSceneWithoutClaims() {
        scene(draft, 2).put("claimIds", List.of());
        assertOnly(Severity.HARD, "REAL_SCENE_NO_CLAIMS");
    }

    @Test
    void hard_realSceneClaimMustBeFactOrEstimate() {
        scene(draft, 0).put("claimIds", List.of("c3")); // HIPOTESIS en el GANCHO
        assertHard("REAL_CLAIM_TYPE");
    }

    @Test
    void hard_ifScenesNeedWhatIfPremise() {
        whatIf(draft).put("premise", " ");
        assertOnly(Severity.HARD, "WHATIF_MISSING");
    }

    @Test
    void hard_ifScenesNeedWhatIfParameters() {
        whatIf(draft).put("parametersFromReal", List.of());
        assertOnly(Severity.HARD, "WHATIF_MISSING");
    }

    @Test
    void hard_ifSceneClaimMustBeHypothesis() {
        scene(draft, 4).put("claimIds", List.of("c1")); // HECHO en la CONSECUENCIA
        assertOnly(Severity.HARD, "IF_CLAIM_TYPE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"animated", "Animation", "DIAGRAM", "illustration", "vector", "cartoon", "infographic",
            "mushroom cloud", "Mushroom  Cloud", "crater", "impact", "crash", "ground blast"})
    void hard_forbiddenVisualTerm(String term) {
        scene(draft, 1).put("visualPrompt", ANCHOR + ", a wide shot with a " + term + " in the distance.");
        assertOnly(Severity.HARD, "FORBIDDEN_VISUAL_TERM");
    }

    @Test
    void forbiddenVisualTerms_matchWholeWordsOnly() {
        scene(draft, 1).put("visualPrompt", ANCHOR + ", impactful vectors of light over craters-free hills.");
        // "impactful" y "vectors" no son las palabras prohibidas; "craters" tampoco es "crater"
        assertThat(codes(validate(), Severity.HARD)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"avoid", "do not", "does not", "don't", "don’t", "must not", "never", "negative prompt", "without", "Without"})
    void hard_negativePromptBlock(String term) {
        scene(draft, 2).put("visualPrompt", ANCHOR + ", a calm forest, " + term + " people in frame.");
        assertOnly(Severity.HARD, "NEGATIVE_PROMPT");
    }

    @Test
    void hard_sceneTextMissing() {
        scene(draft, 3).remove("visualPrompt");
        assertHard("SCENE_TEXT_MISSING");
    }

    @Test
    void hard_topicOrTitleMissing() {
        draft.remove("title");
        assertOnly(Severity.HARD, "TOPIC_TITLE_MISSING");
    }

    @Test
    void hard_malformedSceneBecomesAViolation_notAnException() {
        scenes(draft).set(2, Map.of("role", Map.of("no", "es texto")));
        List<Violation> violations = validate();
        assertThat(codes(violations, Severity.HARD)).contains("SCENE_MALFORMED", "SCENE_COUNT");
    }

    @Test
    void hard_nullSceneOrClaimInsideTheValidator_becomesAViolation_notAnException() {
        validate();
        lastDraft.scenes.set(2, null);
        lastDraft.claims.add(null);
        List<Violation> violations = validator.validate(lastDraft, new DraftValidator.Context(takenIds, existing, researched));
        assertThat(codes(violations, Severity.HARD)).contains("SCENE_MALFORMED", "CLAIM_MALFORMED");
    }

    // --- SOFT ---------------------------------------------------------------------------

    @Test
    void soft_sceneWordsOutsideRoleTable() {
        scene(draft, 0).put("narrationText", words(15)); // GANCHO sugiere 10-14; total 97 y 5.7 s siguen válidos
        List<Violation> violations = validate();
        assertThat(codes(violations, Severity.HARD)).isEmpty();
        assertThat(codes(violations, Severity.SOFT)).containsExactly("SCENE_WORDS_RANGE");
    }

    @Test
    void soft_visualPromptWithoutStyleAnchor() {
        scene(draft, 5).put("visualPrompt", "A calm sunrise over the taiga, one continuous shot.");
        assertOnly(Severity.SOFT, "STYLE_ANCHOR");
        assertThat(codes(validate(), Severity.HARD)).isEmpty();
    }

    @Test
    void soft_sourceUrlNotInResearchedUrls_claimBecomesUnverified() {
        researched = List.of(NASA, USGS, SMITHSONIAN); // BRITANNICA no salió de la búsqueda
        List<Violation> violations = validate();
        assertThat(codes(violations, Severity.HARD)).isEmpty();
        assertThat(codes(violations, Severity.SOFT)).contains("SOURCE_NOT_RESEARCHED");
        StoryDraft.Claim c1 = DraftRules.claimsById(lastDraft).get("c1");
        assertThat(c1.status).isEqualTo("SIN_VERIFICAR");
        assertThat(lastDraft.checks.sourcesVerified).isFalse();
    }

    @Test
    void soft_realClaimWithFewerThanTwoIndependentTierABDomains_forcesLowConfidence() {
        claimById(draft, "c1").put("sources", claim("x", "HECHO", "x", NASA, "https://solarsystem.nasa.gov/otra-pagina").get("sources"));
        researched = List.of(NASA, "https://solarsystem.nasa.gov/otra-pagina", USGS, SMITHSONIAN);
        List<Violation> violations = validate();
        assertThat(codes(violations, Severity.SOFT)).containsExactly("WEAK_SOURCES");
        assertThat(DraftRules.claimsById(lastDraft).get("c1").confidence).isEqualTo("BAJA");
    }

    @Test
    void soft_realClaimBackedOnlyByWikipedia_forcesLowConfidence() {
        String wiki1 = "https://en.wikipedia.org/wiki/Tunguska_event";
        String wiki2 = "https://es.wikipedia.org/wiki/Evento_de_Tunguska";
        claimById(draft, "c1").put("sources", claim("x", "HECHO", "x", wiki1, wiki2).get("sources"));
        researched = List.of(wiki1, wiki2, USGS, SMITHSONIAN);
        List<Violation> violations = validate();
        assertThat(codes(violations, Severity.SOFT)).containsExactly("WEAK_SOURCES");
        assertThat(violations).anyMatch(v -> v.message().contains("Wikipedia"));
        assertThat(DraftRules.claimsById(lastDraft).get("c1").confidence).isEqualTo("BAJA");
        assertThat(DraftRules.claimsById(lastDraft).get("c1").sources).extracting(s -> s.tier).containsOnlyNulls();
    }

    @Test
    void soft_topicOrTitleAlreadyExists() {
        existing = List.of(new ExistingStory("tunguska", "Otra cosa", "  ESTALLIDO de Tunguska de 1908! "));
        assertOnly(Severity.SOFT, "DUPLICATE_TOPIC");
    }

    @Test
    void soft_domainWithoutKnownTier() {
        String blog = "https://misterios-del-cosmos.blogspot.com/tunguska";
        claimById(draft, "c2").put("sources", claim("x", "ESTIMACION", "x", USGS, SMITHSONIAN, blog).get("sources"));
        researched = List.of(NASA, BRITANNICA, USGS, SMITHSONIAN, blog);
        assertOnly(Severity.SOFT, "UNKNOWN_DOMAIN_TIER");
    }

    // --- SOFT: gancho y absolutos ------------------------------------------------------

    /** GANCHO y visualPrompt reales de carrington-1859 (data/stories), generados con las reglas anteriores. */
    private static final String CARRINGTON_HOOK = "En 1859, una aurora boreal se vio en el Caribe, cerca del ecuador.";
    private static final String CARRINGTON_HOOK_PROMPT = ANCHOR + ", one continuous shot slowly tilting upward from a calm "
            + "dark turquoise tropical sea toward a night sky filled with vivid green and red auroral curtains. Silhouettes of "
            + "palm trees and a quiet sandy shoreline sit in the lower foreground. The aurora ripples slowly and glows brightly "
            + "above the warm tropical horizon. Documentary-grade photorealism, natural volumetric light, single continuous shot.";

    @Test
    void soft_hookStartsWithYear_carrington() {
        scene(draft, 0).put("narrationText", CARRINGTON_HOOK); // 13 palabras: total 95, sigue válido
        List<Violation> violations = validate();
        assertThat(codes(violations, Severity.HARD)).isEmpty();
        assertThat(codes(violations, Severity.SOFT)).containsExactly("HOOK_STARTS_WITH_DATE");
        assertThat(violations).allMatch(v -> "tunguska-x-gancho".equals(v.sceneId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1859: auroras en el Caribe.",
            "1000 años después, el bosque sigue marcado.",
            "2099 no es un año cualquiera para este bosque.",
            "1908: un bosque entero cayó sin que nada tocara el suelo, en Siberia.",
            "¿1908? Un bosque entero cayó sin que nada tocara el suelo, en Siberia.",
            "En el año 1908 un bosque entero cayó sin que nada tocara el suelo.",
            "El 30 de junio un bosque entero cayó sin que nada tocara el suelo.",
            "El treinta de junio un bosque entero cayó sin que nada tocara el suelo.",
            "El primero de septiembre un bosque entero cayó sin que nada tocara el suelo.",
            "El treinta y uno de diciembre un bosque cayó sin que nada tocara el suelo."})
    void soft_hookStartsWithDate_variants(String hook) {
        scene(draft, 0).put("narrationText", hook);
        assertThat(codes(validate(), Severity.SOFT)).contains("HOOK_STARTS_WITH_DATE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Auroras boreales en el Caribe. Pasó en 1859.",
            "Un bosque entero cayó en 1908 sin que nada tocara el suelo.",
            "Enorme estallido sobre Siberia, el 30 de junio de 1908.",
            "El bosque de Siberia cayó entero, sin que nada tocara el suelo.",
            // la cifra central al inicio está permitida por las reglas
            "80 millones de árboles cayeron en segundos.",
            "3 de cada 10 árboles cayeron en segundos.",
            // fuera del rango 1000-2099 o más de 4 cifras: no es un año
            "999 árboles cayeron en segundos sobre la taiga.",
            "2100 árboles cayeron en segundos sobre la taiga.",
            "18590 árboles cayeron en segundos sobre la taiga."})
    void hookWithTheSurpriseFirst_isNotFlagged(String hook) {
        scene(draft, 0).put("narrationText", hook);
        assertThat(codes(validate(), Severity.SOFT)).doesNotContain("HOOK_STARTS_WITH_DATE");
    }

    @Test
    void dateStartOutsideTheHook_isNotFlagged() {
        scene(draft, 2).put("narrationText", "En 1908 " + words(16)); // CONTEXTO, 18 palabras
        assertThat(validate()).isEmpty();
    }

    @Test
    void soft_hookSlowReveal_carrington() {
        scene(draft, 0).put("visualPrompt", CARRINGTON_HOOK_PROMPT);
        List<Violation> violations = validate();
        assertThat(codes(violations, Severity.HARD)).isEmpty();
        assertThat(codes(violations, Severity.SOFT)).containsExactly("HOOK_SLOW_REVEAL");
        assertThat(violations).extracting(Violation::message)
                .anyMatch(m -> m.contains("'slowly tilting'"))
                .anyMatch(m -> m.contains("'tilting upward'"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"slowly tilting", "Tilting Upward", "tilt up", "pans to reveal", "pan to reveal", "to reveal",
            "slowly reveals", "STARTING ON A CALM"})
    void soft_hookSlowReveal_eachTerm(String term) {
        scene(draft, 0).put("visualPrompt", ANCHOR + ", one continuous shot over the taiga, " + term + " the burst.");
        assertOnly(Severity.SOFT, "HOOK_SLOW_REVEAL");
    }

    @Test
    void hookFromTheFirstFrame_orRevealOutsideTheHook_isNotFlagged() {
        scene(draft, 0).put("visualPrompt", ANCHOR + ", one continuous shot. From the very first frame, vivid green auroral "
                + "curtains fill the night sky above a tropical sea, while the camera keeps tilting gently.");
        scene(draft, 2).put("visualPrompt", CARRINGTON_HOOK_PROMPT); // CONTEXTO: la regla es solo del GANCHO
        assertThat(validate()).isEmpty();
    }

    @Test
    void soft_absoluteInTitle_carrington() {
        draft.put("title", "1859: la tormenta solar más intensa jamás registrada");
        List<Violation> violations = validate();
        assertThat(codes(violations, Severity.HARD)).isEmpty();
        assertThat(codes(violations, Severity.SOFT)).containsExactly("ABSOLUTE_TERM");
        assertThat(violations).allMatch(v -> v.sceneId() == null && v.message().contains("title"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void soft_absoluteInRealSceneNarration(int index) {
        String narration = (String) scene(draft, index).get("narrationText");
        scene(draft, index).put("narrationText", narration.replaceFirst("palabra", "NUNCA")); // mismas palabras
        List<Violation> violations = validate();
        assertThat(codes(violations, Severity.SOFT)).containsExactly("ABSOLUTE_TERM");
        assertThat(violations).allMatch(v -> v.sceneId().equals(scene(draft, index).get("id")));
    }

    @Test
    void absolutesInIfScenes_orInsideOtherWords_areNotFlagged() {
        scene(draft, 3).put("narrationText", "Jamás " + words(14)); // GIRO (IF): la regla es de las escenas 1-3
        scene(draft, 4).put("narrationText", "nunca " + words(17)); // CONSECUENCIA (IF)
        scene(draft, 1).put("narrationText", "Jamaica nuncajamás " + words(17)); // no son la palabra completa
        draft.put("title", "Jamaica bajo la aurora");
        assertThat(validate()).isEmpty();
    }

    // --- acknowledgeUnverified ----------------------------------------------------------

    @Test
    void unverifiedOrLowConfidenceClaims_ignoreHypotheses() {
        validate();
        assertThat(DraftRules.unverifiedOrLowConfidenceClaimIds(lastDraft)).isEmpty(); // c3 es HIPOTESIS sin fuentes
        researched = List.of(NASA, BRITANNICA);
        validate();
        assertThat(DraftRules.unverifiedOrLowConfidenceClaimIds(lastDraft)).containsExactly("c2");
    }
}
