package com.aishorts.engine.drafts;

import com.aishorts.engine.domain.SceneRole;
import com.aishorts.engine.script.SceneDraft;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.aishorts.engine.drafts.DraftFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** POST /story-drafts/{id}/decision (PUERTA 0) y el flujo completo sin servicios pagos del pipeline. */
@SuppressWarnings({"rawtypes", "unchecked"})
class StoryDraftDecisionApiTest extends DraftApiTestSupport {

    private static final String ID = "tunguska-x";

    @Test
    void approve_createsTheSameStoryAsPostStories_withOnlyPipelineFields() throws Exception {
        generate(validDraft(ID, "Estallido de Tunguska"), RESEARCHED);

        ResponseEntity<Map> response = post("/story-drafts/" + ID + "/decision", Map.of("decision", "APPROVE"));

        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(200);
        Map<String, Object> draft = (Map<String, Object>) response.getBody().get("draft");
        assertThat(draft.get("status")).isEqualTo("APPROVED");
        assertThat(draft.get("approvedStoryId")).isEqualTo(ID);
        Map<String, Object> approvedStory = getMap("/stories/" + ID).getBody();
        String storyFile = Files.readString(storiesDir().resolve(ID + ".json"));
        for (String draftOnlyField : List.of("claims", "sources", "whatIf", "researchedUrls", "violations", "visualConstraints", "claimIds")) {
            assertThat(storyFile).doesNotContain("\"" + draftOnlyField + "\"");
        }
        List<Map<String, Object>> scenes = (List<Map<String, Object>>) approvedStory.get("scenes");
        assertThat(scenes).extracting(s -> s.get("id")).containsExactly(ID + "-gancho", ID + "-explicacion", ID + "-contexto",
                ID + "-giro", ID + "-consecuencia", ID + "-cierre");
        assertThat(scenes).allMatch(s -> "PENDING".equals(s.get("promptStatus")) && s.get("chosenTier") == null);

        // La misma Story, creada por POST /stories con los mismos datos (el guionado devuelve las mismas escenas).
        Files.delete(storiesDir().resolve(ID + ".json"));
        List<SceneDraft> sameScenes = new ArrayList<>();
        for (Map<String, Object> scene : scenes) {
            sameScenes.add(new SceneDraft(SceneRole.valueOf((String) scene.get("role")),
                    (String) scene.get("narrationText"), (String) scene.get("visualPrompt")));
        }
        when(scriptDraftingService.draftScenes(any())).thenReturn(sameScenes);
        ResponseEntity<Map> viaPostStories = post("/stories", Map.of(
                "storyId", ID,
                "topic", approvedStory.get("topic"),
                "title", approvedStory.get("title"),
                "coreFacts", List.of("cualquier hecho"),
                "durationBudget", Map.of("minSeconds", 35, "maxSeconds", 40)));
        assertThat(viaPostStories.getStatusCode().value()).isEqualTo(201);
        assertThat(viaPostStories.getBody()).isEqualTo(approvedStory);
    }

    @Test
    void secondDecision_is409() {
        generate(validDraft(ID, "Estallido de Tunguska"), RESEARCHED);
        assertThat(decide(Map.of("decision", "APPROVE")).getStatusCode().value()).isEqualTo(200);
        assertThat(decide(Map.of("decision", "APPROVE")).getStatusCode().value()).isEqualTo(409);
        assertThat(decide(Map.of("decision", "REJECT", "note", "tarde")).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void reject_requiresANonBlankNote() {
        generate(validDraft(ID, "Estallido de Tunguska"), RESEARCHED);
        assertThat(decide(Map.of("decision", "REJECT")).getStatusCode().value()).isEqualTo(400);
        assertThat(decide(Map.of("decision", "REJECT", "note", "   ")).getStatusCode().value()).isEqualTo(400);
        assertThat(getMap("/story-drafts/" + ID).getBody().get("status")).isEqualTo("PENDING_REVIEW");

        ResponseEntity<Map> rejected = decide(Map.of("decision", "REJECT", "note", "El gancho exagera la cifra."));
        assertThat(rejected.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> draft = (Map<String, Object>) rejected.getBody().get("draft");
        assertThat(draft.get("status")).isEqualTo("REJECTED");
        assertThat(draft.get("rejectionNote")).isEqualTo("El gancho exagera la cifra.");
        assertThat(rejected.getBody().get("story")).isNull();
    }

    @Test
    void rejectThenRegenerate_passesTheRejectionNoteToTheModel() {
        generate(validDraft(ID, "Estallido de Tunguska"), RESEARCHED);
        decide(Map.of("decision", "REJECT", "note", "El gancho exagera la cifra de árboles."));
        fake.reset();

        fake.enqueue(json(validDraft("tunguska-y", "Estallido de Tunguska, versión 2")), RESEARCHED);
        ResponseEntity<Map> regenerated = post("/story-drafts", Map.of("topic", "Tunguska", "rejectedDraftId", ID));

        assertThat(regenerated.getStatusCode().value()).isEqualTo(201);
        assertThat(regenerated.getBody().get("rejectedDraftId")).isEqualTo(ID);
        assertThat(regenerated.getBody().get("reviewerFeedback")).isEqualTo("El gancho exagera la cifra de árboles.");
        assertThat(fake.requests().get(0).userMessage()).contains("RECHAZÓ").contains("El gancho exagera la cifra de árboles.");
        // el rechazado no figura en existingStories
        assertThat(fake.requests().get(0).systemPrompt()).doesNotContain("\"id\":\"" + ID + "\"");
    }

    @Test
    void regenerate_fromADraftThatIsNotRejected_is409_andFromAnUnknownDraft_is404() {
        generate(validDraft(ID, "Estallido de Tunguska"), RESEARCHED);
        fake.reset();
        assertThat(post("/story-drafts", Map.of("topic", "t", "rejectedDraftId", ID)).getStatusCode().value()).isEqualTo(409);
        assertThat(post("/story-drafts", Map.of("topic", "t", "rejectedDraftId", "no-existe")).getStatusCode().value()).isEqualTo(404);
        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void approve_withHardViolations_is422_andCreatesNothing() throws Exception {
        Map<String, Object> bad = validDraft(ID, "Estallido de Tunguska");
        scene(bad, 5).put("narrationText", words(8)); // TOTAL_WORDS, en las dos pasadas
        generate(bad, RESEARCHED);

        ResponseEntity<Map> response = decide(Map.of("decision", "APPROVE", "acknowledgeUnverified", true));

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        List<Map<String, Object>> violations = (List<Map<String, Object>>) response.getBody().get("violations");
        assertThat(violations).extracting(v -> v.get("code")).containsExactly("TOTAL_WORDS");
        assertThat(Files.list(storiesDir()).count()).isZero();
        assertThat(getMap("/story-drafts/" + ID).getBody().get("status")).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void approve_appliesEditsAndRevalidates() {
        Map<String, Object> bad = validDraft(ID, "Estallido de Tunguska");
        scene(bad, 0).put("narrationText", words(22)); // SCENE_DURATION en el GANCHO
        generate(bad, RESEARCHED);
        String fixedHook = "Un estallido en el cielo tumbó millones de árboles en Siberia, sin dejar cráter.";

        ResponseEntity<Map> response = decide(Map.of("decision", "APPROVE",
                "narrationEdits", List.of(Map.of("sceneId", ID + "-gancho", "narrationText", fixedHook)),
                "promptEdits", List.of(Map.of("sceneId", ID + "-cierre", "visualPrompt", ANCHOR + ", a quiet observatory at night."))));

        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(200);
        Map<String, Object> story = (Map<String, Object>) response.getBody().get("story");
        List<Map<String, Object>> scenes = (List<Map<String, Object>>) story.get("scenes");
        assertThat(scenes.get(0).get("narrationText")).isEqualTo(fixedHook);
        assertThat(scenes.get(5).get("visualPrompt")).isEqualTo(ANCHOR + ", a quiet observatory at night.");
        Map<String, Object> draft = (Map<String, Object>) response.getBody().get("draft");
        assertThat(((List<Map<String, Object>>) draft.get("scenes")).get(0).get("words")).isEqualTo(14);
        assertThat((List<?>) draft.get("violations")).isEmpty();
    }

    @Test
    void approve_anEditThatBreaksAHardRule_is422_andTheDraftIsUnchanged() {
        generate(validDraft(ID, "Estallido de Tunguska"), RESEARCHED);

        ResponseEntity<Map> response = decide(Map.of("decision", "APPROVE",
                "promptEdits", List.of(Map.of("sceneId", ID + "-giro", "visualPrompt", ANCHOR + ", a cartoon city."))));

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat((List<Map<String, Object>>) response.getBody().get("violations"))
                .extracting(v -> v.get("code")).containsExactly("FORBIDDEN_VISUAL_TERM");
        Map<String, Object> stored = getMap("/story-drafts/" + ID).getBody();
        assertThat(stored.get("status")).isEqualTo("PENDING_REVIEW");
        assertThat(((List<Map<String, Object>>) stored.get("scenes")).get(3).get("visualPrompt").toString()).doesNotContain("cartoon");
    }

    @Test
    void edit_withUnknownSceneId_is400() {
        generate(validDraft(ID, "Estallido de Tunguska"), RESEARCHED);
        assertThat(decide(Map.of("decision", "APPROVE",
                "narrationEdits", List.of(Map.of("sceneId", "otra-gancho", "narrationText", "x")))).getStatusCode().value()).isEqualTo(400);
        assertThat(decide(Map.of("decision", "REJECT", "note", "n",
                "promptEdits", List.of(Map.of("sceneId", "otra-giro", "visualPrompt", "x")))).getStatusCode().value()).isEqualTo(400);
        assertThat(getMap("/story-drafts/" + ID).getBody().get("status")).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void approve_whenAStoryWithThatIdAlreadyExists_is409_andTheStoryIsUntouched() throws Exception {
        generate(validDraft(ID, "Estallido de Tunguska"), RESEARCHED);
        StoryListApiTest.writeStoryFile(storiesDir(), ID, "Otra historia distinta");
        byte[] before = Files.readAllBytes(storiesDir().resolve(ID + ".json"));

        assertThat(decide(Map.of("decision", "APPROVE")).getStatusCode().value()).isEqualTo(409);

        assertThat(Files.readAllBytes(storiesDir().resolve(ID + ".json"))).isEqualTo(before);
        assertThat(getMap("/story-drafts/" + ID).getBody().get("status")).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void approve_withUnverifiedOrLowConfidenceClaims_requiresAcknowledgeUnverified() {
        generate(validDraft(ID, "Estallido de Tunguska"), List.of(NASA, USGS, SMITHSONIAN)); // BRITANNICA sin consultar

        ResponseEntity<Map> withoutAck = decide(Map.of("decision", "APPROVE"));
        assertThat(withoutAck.getStatusCode().value()).isEqualTo(422);
        assertThat((String) withoutAck.getBody().get("error")).contains("acknowledgeUnverified").contains("c1");
        assertThat(Files.exists(storiesDir().resolve(ID + ".json"))).isFalse();

        assertThat(decide(Map.of("decision", "APPROVE", "acknowledgeUnverified", true)).getStatusCode().value()).isEqualTo(200);
        assertThat(Files.exists(storiesDir().resolve(ID + ".json"))).isTrue();
    }

    @Test
    void invalidDecisionBodies_are400() {
        generate(validDraft(ID, "Estallido de Tunguska"), RESEARCHED);
        assertThat(decide(Map.of()).getStatusCode().value()).isEqualTo(400);
        assertThat(decide(Map.of("decision", "MAYBE")).getStatusCode().value()).isEqualTo(400);
        assertThat(decide(Map.of("decision", "APPROVE", "acknowledgeUnverified", "yes")).getStatusCode().value()).isEqualTo(400);
        assertThat(decide(Map.of("decision", "APPROVE", "narrationEdits", "no-es-lista")).getStatusCode().value()).isEqualTo(400);
        assertThat(post("/story-drafts/no-existe/decision", Map.of("decision", "APPROVE")).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void wholeFlow_neverTouchesHiggsfieldElevenLabsOrThePostStoriesScriptWriter() {
        fake.enqueue(proposalsJson("Tunguska uno", "Krakatoa dos", "Tambora tres", "Valdivia cuatro", "Cheliábinsk cinco"), List.of(NASA));
        assertThat(post("/story-drafts/proposals", Map.of()).getStatusCode().value()).isEqualTo(200);
        generate(validDraft(ID, "Estallido de Tunguska"), RESEARCHED);
        assertThat(decide(Map.of("decision", "REJECT", "note", "otra vez")).getStatusCode().value()).isEqualTo(200);
        fake.enqueue(json(validDraft("tunguska-y", "Estallido de Tunguska")), RESEARCHED);
        assertThat(post("/story-drafts", Map.of("topic", "Tunguska", "rejectedDraftId", ID)).getStatusCode().value()).isEqualTo(201);
        assertThat(post("/story-drafts/tunguska-y/decision", Map.of("decision", "APPROVE")).getStatusCode().value()).isEqualTo(200);
        assertThat(getList("/stories").getBody()).hasSize(1);
        assertThat(getList("/story-drafts").getBody()).hasSize(2);
        assertThat(getMap("/stories/tunguska-y").getStatusCode().value()).isEqualTo(200);

        verifyNoInteractions(higgsfieldClient, ttsService, scriptDraftingService);
    }

    // --- helpers ---------------------------------------------------------------------------

    /** Genera un borrador vía API; el fake devuelve el mismo JSON en la pasada y en el (eventual) reintento. */
    private void generate(Map<String, Object> draftJson, List<String> researched) {
        fake.enqueue(json(draftJson), researched);
        fake.enqueue(json(draftJson), List.of());
        ResponseEntity<Map> response = post("/story-drafts", Map.of("topic", "Tunguska"));
        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(201);
        fake.reset();
    }

    private ResponseEntity<Map> decide(Map<String, Object> body) {
        return post("/story-drafts/" + ID + "/decision", body);
    }
}
