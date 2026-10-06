package com.aishorts.engine.drafts;

import com.aishorts.engine.drafts.StoryDraftGenerator.GeneratorRequest;
import com.aishorts.engine.drafts.StoryDraftGenerator.Mode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.aishorts.engine.drafts.DraftFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/** POST /story-drafts/proposals, POST /story-drafts (con reintento y colisión de slug) y GET /story-drafts. */
@SuppressWarnings({"rawtypes", "unchecked"})
class StoryDraftGenerationApiTest extends DraftApiTestSupport {

    // --- propuestas ------------------------------------------------------------------------

    @Test
    void proposals_returnsFiveCandidates_passesExistingStoriesToTheModel_andMarksOverlap() throws Exception {
        writeStory("tunguska", "Explosión de Tunguska (1908)");
        createDraft("Volcán Krakatoa", validDraft("krakatoa", "Erupción del Krakatoa de 1883"));
        createDraft("Meteorito de Cheliábinsk", validDraft("cheliabinsk", "Meteorito de Cheliábinsk"));
        reject("cheliabinsk");
        fake.reset();

        fake.enqueue(proposalsJson("Explosión de Tunguska (1908)", "Terremoto de Valdivia", "Tsunami de 2004",
                "Erupción del Tambora", "Gran Mancha Roja"), List.of(NASA));

        ResponseEntity<Map> response = post("/story-drafts/proposals", Map.of("focus", "desastres naturales"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.getBody().get("candidates");
        assertThat(candidates).hasSize(5);
        assertThat(candidates.get(0).get("overlapWithExisting")).isEqualTo(true);
        assertThat(candidates.get(0).get("overlapsWith")).isEqualTo(List.of("tunguska"));
        assertThat(candidates.subList(1, 5)).allMatch(c -> Boolean.FALSE.equals(c.get("overlapWithExisting")));

        GeneratorRequest request = fake.requests().get(0);
        assertThat(request.mode()).isEqualTo(Mode.PROPOSALS);
        assertThat(request.webSearch()).isTrue();
        assertThat(request.maxSearchUses()).isEqualTo(3);
        assertThat(request.userMessage()).contains("desastres naturales");
        assertThat(request.systemPrompt())
                .contains("\"id\":\"tunguska\"").contains("Explosión de Tunguska (1908)")
                .contains("\"id\":\"krakatoa\"")
                .doesNotContain("\"id\":\"cheliabinsk\""); // los rechazados no cuentan
        assertThat(Files.list(draftsDir()).count()).isEqualTo(2); // las propuestas no se guardan
    }

    @Test
    void proposals_wrongNumberOfCandidates_is502() {
        fake.enqueue(proposalsJson("a uno", "b dos", "c tres", "d cuatro"), List.of());
        ResponseEntity<Map> response = post("/story-drafts/proposals", Map.of());
        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat((String) response.getBody().get("error")).contains("4 candidatos");
    }

    @Test
    void proposals_candidateMissingAField_is502() {
        String json = proposalsJson("a uno", "b dos", "c tres", "d cuatro", "e cinco").replaceFirst("\"realEvent\"", "\"otro\"");
        fake.enqueue(json, List.of());
        ResponseEntity<Map> response = post("/story-drafts/proposals", null);
        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat((String) response.getBody().get("error")).contains("realEvent");
    }

    @Test
    void proposals_generatorFailure_is502WithItsMessage() {
        fake.enqueueFailure(new DraftException(502, "La búsqueda web no está habilitada en la organización de Claude."));
        ResponseEntity<Map> response = post("/story-drafts/proposals", Map.of());
        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat((String) response.getBody().get("error")).contains("búsqueda web no está habilitada");
    }

    // --- generación feliz ------------------------------------------------------------------

    @Test
    void generate_happyPath_savesPendingReviewDraftWithServerComputedFields_andNoStory() throws Exception {
        fake.enqueue(json(validDraft("tunguska-x", "Estallido de Tunguska")), RESEARCHED);

        ResponseEntity<Map> response = post("/story-drafts", Map.of("topic", "Tunguska"));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("id")).isEqualTo("tunguska-x");
        assertThat(body.get("status")).isEqualTo("PENDING_REVIEW");
        assertThat(body.get("attempts")).isEqualTo(1);
        assertThat((List<?>) body.get("violations")).isEmpty();
        assertThat(body.get("researchedUrls")).isEqualTo(RESEARCHED);
        assertThat(body.get("usage")).isEqualTo(Map.of("inputTokens", 1000, "outputTokens", 500, "webSearchRequests", 2));

        // words/checks del modelo (99 y totalWords 1) ignorados y recalculados
        List<Map<String, Object>> scenes = (List<Map<String, Object>>) body.get("scenes");
        assertThat(scenes).extracting(s -> s.get("words")).containsExactly(12, 19, 18, 15, 18, 12);
        Map<String, Object> checks = (Map<String, Object>) body.get("checks");
        assertThat(checks.get("totalWords")).isEqualTo(94);
        assertThat(checks.get("sourcesVerified")).isEqualTo(true);
        assertThat(checks.get("violations")).isEqualTo(List.of());

        assertThat(draftsDir().resolve("tunguska-x.json")).exists();
        assertThat(Files.list(storiesDir()).count()).isZero();

        GeneratorRequest request = fake.requests().get(0);
        assertThat(fake.requests()).hasSize(1);
        assertThat(request.mode()).isEqualTo(Mode.DRAFT);
        assertThat(request.webSearch()).isTrue();
        assertThat(request.maxSearchUses()).isEqualTo(10);
        assertThat(request.systemPrompt()).startsWith("# INSTRUCCIONES PARA LA IA: generador de historias")
                .contains("Modo: BORRADOR").contains("Fecha de hoy: 2026-10-06").contains("Tienes búsqueda web");
        assertThat(request.userMessage()).contains("Tema elegido: Tunguska");

        assertThat(getMap("/story-drafts/tunguska-x").getBody().get("title")).isEqualTo("El estallido que tumbó un bosque entero");
    }

    @Test
    void generate_missingTopic_is400() {
        assertThat(post("/story-drafts", Map.of()).getStatusCode().value()).isEqualTo(400);
        assertThat(post("/story-drafts", Map.of("topic", "  ")).getStatusCode().value()).isEqualTo(400);
        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void generate_generatorFailure_is502_andNothingIsSaved() throws Exception {
        fake.enqueueFailure(new DraftException(502, "Falló la llamada a Claude: timeout"));
        ResponseEntity<Map> response = post("/story-drafts", Map.of("topic", "Tunguska"));
        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(Files.list(draftsDir()).count()).isZero();
    }

    // --- reintento -------------------------------------------------------------------------

    @Test
    void retry_failsThenPasses_attempts2_feedbackSentWithoutTools_andResearchedUrlsKept() {
        Map<String, Object> bad = validDraft("tunguska-x", "Estallido de Tunguska");
        scene(bad, 5).put("narrationText", words(8)); // total 90 -> TOTAL_WORDS
        String badJson = json(bad);
        fake.enqueue(badJson, RESEARCHED);
        fake.enqueue(json(validDraft("tunguska-x", "Estallido de Tunguska")), List.of());

        Map<String, Object> body = post("/story-drafts", Map.of("topic", "Tunguska")).getBody();

        assertThat(body.get("attempts")).isEqualTo(2);
        assertThat((List<?>) body.get("violations")).isEmpty();
        assertThat(body.get("researchedUrls")).isEqualTo(RESEARCHED);
        assertThat(((Map<String, Object>) body.get("checks")).get("sourcesVerified")).isEqualTo(true);
        assertThat(body.get("usage")).isEqualTo(Map.of("inputTokens", 2000, "outputTokens", 1000, "webSearchRequests", 2));

        assertThat(fake.requests()).hasSize(2);
        GeneratorRequest retry = fake.requests().get(1);
        assertThat(retry.mode()).isEqualTo(Mode.DRAFT_RETRY);
        assertThat(retry.webSearch()).isFalse();
        assertThat(retry.userMessage()).contains("[TOTAL_WORDS]").contains(badJson);
        assertThat(retry.systemPrompt()).contains("NO tienes búsqueda web");
    }

    @Test
    void retry_failsTwice_savesDraftWithHardViolations_generatorCalledExactlyTwice() {
        Map<String, Object> bad = validDraft("tunguska-x", "Estallido de Tunguska");
        scene(bad, 1).put("visualPrompt", ANCHOR + ", an animated diagram of the blast.");
        fake.enqueue(json(bad), RESEARCHED);
        fake.enqueue(json(bad), List.of());

        ResponseEntity<Map> response = post("/story-drafts", Map.of("topic", "Tunguska"));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("status")).isEqualTo("PENDING_REVIEW");
        assertThat(body.get("attempts")).isEqualTo(2);
        List<Map<String, Object>> violations = (List<Map<String, Object>>) body.get("violations");
        assertThat(violations).filteredOn(v -> "HARD".equals(v.get("severity")))
                .extracting(v -> v.get("code")).containsOnly("FORBIDDEN_VISUAL_TERM");
        assertThat(fake.requests()).hasSize(2);
        assertThat(draftsDir().resolve("tunguska-x.json")).exists();
    }

    @Test
    void retry_invalidJsonTwice_savesUnderASlugOfTheTopic_withTheRawText() {
        fake.enqueue("No pude generar el borrador.", List.of(NASA));
        fake.enqueue("Sigo sin poder.", List.of());

        Map<String, Object> body = post("/story-drafts", Map.of("topic", "Erupción del Tambora")).getBody();

        assertThat(body.get("id")).isEqualTo("erupcion-del-tambora");
        assertThat(body.get("unparsedResponse")).isEqualTo("Sigo sin poder.");
        List<Map<String, Object>> violations = (List<Map<String, Object>>) body.get("violations");
        assertThat(violations).extracting(v -> v.get("code")).contains("JSON_INVALID", "SLUG_REASSIGNED");
        assertThat(fake.requests()).hasSize(2);
    }

    @Test
    void slugCollision_neverOverwrites_renamesAndReprefixesSceneIds() throws Exception {
        writeStory("tunguska", "Explosión de Tunguska (1908)");
        byte[] storyBefore = Files.readAllBytes(storiesDir().resolve("tunguska.json"));
        fake.enqueue(json(validDraft("tunguska", "Otro ángulo de Tunguska")), RESEARCHED);
        fake.enqueue(json(validDraft("tunguska", "Otro ángulo de Tunguska")), List.of());

        Map<String, Object> first = post("/story-drafts", Map.of("topic", "Tunguska")).getBody();

        assertThat(first.get("id")).isEqualTo("tunguska-2");
        assertThat(((List<Map<String, Object>>) first.get("scenes"))).extracting(s -> s.get("id"))
                .containsExactly("tunguska-2-gancho", "tunguska-2-explicacion", "tunguska-2-contexto",
                        "tunguska-2-giro", "tunguska-2-consecuencia", "tunguska-2-cierre");
        List<Map<String, Object>> violations = (List<Map<String, Object>>) first.get("violations");
        assertThat(violations).extracting(v -> v.get("code")).containsExactly("SLUG_RENAMED");
        assertThat(violations.get(0).get("severity")).isEqualTo("SOFT");
        assertThat(fake.requests()).hasSize(2); // SLUG_TAKEN es dura: hubo reintento
        assertThat(fake.requests().get(1).userMessage()).contains("[SLUG_TAKEN]");
        assertThat(Files.readAllBytes(storiesDir().resolve("tunguska.json"))).isEqualTo(storyBefore);

        // un segundo borrador con el mismo slug tampoco pisa al primero
        byte[] draftBefore = Files.readAllBytes(draftsDir().resolve("tunguska-2.json"));
        fake.enqueue(json(validDraft("tunguska", "Tercer ángulo")), RESEARCHED);
        fake.enqueue(json(validDraft("tunguska", "Tercer ángulo")), List.of());
        assertThat(post("/story-drafts", Map.of("topic", "Tunguska")).getBody().get("id")).isEqualTo("tunguska-3");
        assertThat(Files.readAllBytes(draftsDir().resolve("tunguska-2.json"))).isEqualTo(draftBefore);
    }

    // --- GET /story-drafts -----------------------------------------------------------------

    @Test
    void listDrafts_summarizesEach_andToleratesACorruptFile() throws Exception {
        createDraft("Tunguska", validDraft("tunguska-x", "Estallido de Tunguska"));
        Files.writeString(draftsDir().resolve("roto.json"), "{no es json", StandardCharsets.UTF_8);

        List<Map<String, Object>> list = getList("/story-drafts").getBody();

        assertThat(list).hasSize(2);
        assertThat(list).anySatisfy(d -> {
            assertThat(d.get("id")).isEqualTo("tunguska-x");
            assertThat(d.get("status")).isEqualTo("PENDING_REVIEW");
            assertThat(d.get("hardViolations")).isEqualTo(0);
            assertThat(d.get("softViolations")).isEqualTo(0);
        });
        assertThat(list).anySatisfy(d -> {
            assertThat(d.get("id")).isEqualTo("roto");
            assertThat(d).containsKey("error");
        });
    }

    @Test
    void getDraft_unknownId_is404_andInvalidIds_are400() {
        assertThat(getMap("/story-drafts/no-existe").getStatusCode().value()).isEqualTo(404);
        for (String badId : List.of("..evil", "Tunguska", "a_b", "a--b", "-a", "a".repeat(41), "..%5C..%5Cwindows")) {
            ResponseEntity<Map> get = getMap("/story-drafts/" + badId);
            assertThat(get.getStatusCode().value()).as(badId).isEqualTo(400);
            assertThat((String) get.getBody().get("error")).as(badId).contains("minúsculas, dígitos y guiones");
            assertThat(post("/story-drafts/" + badId + "/decision", Map.of("decision", "REJECT", "note", "x"))
                    .getStatusCode().value()).as(badId).isEqualTo(400);
        }
        assertThat(post("/story-drafts", Map.of("topic", "t", "rejectedDraftId", "../stories/tunguska"))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(fake.requests()).isEmpty();
    }

    // --- helpers ---------------------------------------------------------------------------

    private void createDraft(String topic, Map<String, Object> draftJson) {
        fake.enqueue(json(draftJson), RESEARCHED);
        ResponseEntity<Map> response = post("/story-drafts", Map.of("topic", topic));
        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(201);
    }

    private void reject(String draftId) {
        assertThat(post("/story-drafts/" + draftId + "/decision", Map.of("decision", "REJECT", "note", "no me convence"))
                .getStatusCode().value()).isEqualTo(200);
    }

    static void writeStory(String id, String topic) throws Exception {
        StoryListApiTest.writeStoryFile(storiesDir(), id, topic);
    }

    static Path draftFile(String id) {
        return draftsDir().resolve(id + ".json");
    }
}
