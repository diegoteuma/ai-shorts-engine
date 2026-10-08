package com.aishorts.engine.drafts;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static com.aishorts.engine.drafts.DraftFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/** PATCH /story-drafts/{id}: editar un borrador PENDING_REVIEW y aprobarlo después, sin regenerarlo. */
@SuppressWarnings({"rawtypes", "unchecked"})
class StoryDraftEditApiTest extends DraftApiTestSupport {

    private static final String ID = "tunguska-x";
    private static final String NEW_HOOK = "Una mañana algo estalló sobre Siberia y nadie supo jamás qué fue";

    @Test
    void edit_persistsTitleNarrationAndPrompt_recalculates_andApproveUsesTheEditedText() {
        generate();
        String newPrompt = ANCHOR + ", a lone reindeer herder looking at a flattened forest, soft morning haze.";

        ResponseEntity<Map> response = patch(Map.of(
                "title", "  El día que el cielo cayó sobre Siberia  ",
                "narrationEdits", List.of(Map.of("sceneId", ID + "-gancho", "narrationText", NEW_HOOK)),
                "promptEdits", List.of(Map.of("sceneId", ID + "-giro", "visualPrompt", newPrompt))));

        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(200);
        Map<String, Object> stored = getMap("/story-drafts/" + ID).getBody();
        assertThat(stored.get("status")).isEqualTo("PENDING_REVIEW");
        assertThat(stored.get("title")).isEqualTo("El día que el cielo cayó sobre Siberia");
        assertThat(stored.get("editedAt")).isNotNull();
        List<Map<String, Object>> scenes = (List<Map<String, Object>>) stored.get("scenes");
        assertThat(scenes.get(0).get("narrationText")).isEqualTo(NEW_HOOK);
        assertThat(scenes.get(0).get("words")).isEqualTo(12);
        assertThat(scenes.get(3).get("visualPrompt")).isEqualTo(newPrompt);
        assertThat((List<?>) stored.get("violations")).isEmpty();

        ResponseEntity<Map> approved = post("/story-drafts/" + ID + "/decision",
                Map.of("decision", "APPROVE", "acknowledgeUnverified", true));
        assertThat(approved.getStatusCode().value()).as(String.valueOf(approved.getBody())).isEqualTo(200);
        Map<String, Object> story = getMap("/stories/" + ID).getBody();
        assertThat(story.get("title")).isEqualTo("El día que el cielo cayó sobre Siberia");
        List<Map<String, Object>> storyScenes = (List<Map<String, Object>>) story.get("scenes");
        assertThat(storyScenes.get(0).get("narrationText")).isEqualTo(NEW_HOOK);
        assertThat(storyScenes.get(3).get("visualPrompt")).isEqualTo(newPrompt);
        verifyNoInteractions(higgsfieldClient, ttsService, scriptDraftingService);
    }

    @Test
    void edit_withHardViolation_isSavedAndVisible_butApproveIsBlockedUntilFixed() {
        generate();

        ResponseEntity<Map> broken = patch(Map.of(
                "promptEdits", List.of(Map.of("sceneId", ID + "-giro", "visualPrompt", ANCHOR + ", a cartoon city."))));
        assertThat(broken.getStatusCode().value()).isEqualTo(200);
        assertThat((List<Map<String, Object>>) broken.getBody().get("violations"))
                .extracting(v -> v.get("code")).containsExactly("FORBIDDEN_VISUAL_TERM");
        assertThat(getList("/story-drafts").getBody()).singleElement()
                .satisfies(s -> assertThat(((Map<String, Object>) s).get("hardViolations")).isEqualTo(1));
        assertThat(post("/story-drafts/" + ID + "/decision", Map.of("decision", "APPROVE", "acknowledgeUnverified", true))
                .getStatusCode().value()).isEqualTo(422);

        ResponseEntity<Map> fixed = patch(Map.of("promptEdits", List.of(Map.of("sceneId", ID + "-giro",
                "visualPrompt", ANCHOR + ", a realistic city skyline at dawn."))));
        assertThat((List<?>) fixed.getBody().get("violations")).isEmpty();
        assertThat(post("/story-drafts/" + ID + "/decision", Map.of("decision", "APPROVE", "acknowledgeUnverified", true))
                .getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void edit_ofADecidedDraft_is409() throws Exception {
        generate();
        assertThat(post("/story-drafts/" + ID + "/decision", Map.of("decision", "REJECT", "note", "no"))
                .getStatusCode().value()).isEqualTo(200);
        byte[] before = Files.readAllBytes(draftsDir().resolve(ID + ".json"));

        assertThat(patch(Map.of("title", "Otro título")).getStatusCode().value()).isEqualTo(409);
        assertThat(Files.readAllBytes(draftsDir().resolve(ID + ".json"))).isEqualTo(before);
    }

    @Test
    void invalidEditBodies_are400_andLeaveTheDraftUntouched() throws Exception {
        generate();
        byte[] before = Files.readAllBytes(draftsDir().resolve(ID + ".json"));

        assertThat(patch(Map.of()).getStatusCode().value()).isEqualTo(400);
        assertThat(patch(Map.of("title", "   ")).getStatusCode().value()).isEqualTo(400);
        assertThat(patch(Map.of("title", 5)).getStatusCode().value()).isEqualTo(400);
        assertThat(patch(Map.of("narrationEdits", "no-es-lista")).getStatusCode().value()).isEqualTo(400);
        assertThat(patch(Map.of("narrationEdits", List.of(Map.of("sceneId", "otra-gancho", "narrationText", "x"))))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(patch(Map.of("title", "Válido", "promptEdits", List.of(Map.of("sceneId", ID + "-giro", "visualPrompt", " "))))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(Files.readAllBytes(draftsDir().resolve(ID + ".json"))).isEqualTo(before);

        assertThat(patch("/story-drafts/no-existe", Map.of("title", "x")).getStatusCode().value()).isEqualTo(404);
    }

    // --- helpers ---------------------------------------------------------------------------

    private void generate() {
        String draftJson = json(validDraft(ID, "Estallido de Tunguska"));
        fake.enqueue(draftJson, RESEARCHED);
        fake.enqueue(draftJson, List.of());
        ResponseEntity<Map> response = post("/story-drafts", Map.of("topic", "Tunguska"));
        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(201);
        fake.reset();
    }

    private ResponseEntity<Map> patch(Map<String, Object> body) {
        return patch("/story-drafts/" + ID, body);
    }

    /** HttpURLConnection (el cliente por defecto de TestRestTemplate) no soporta PATCH; el de la JDK sí. */
    private ResponseEntity<Map> patch(String path, Map<String, Object> body) {
        RestTemplate client = rest.getRestTemplate();
        RestTemplate patchClient = new RestTemplate(new JdkClientHttpRequestFactory());
        patchClient.setErrorHandler(client.getErrorHandler());
        patchClient.setMessageConverters(client.getMessageConverters());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return patchClient.exchange("http://localhost:" + port + path, HttpMethod.PATCH, new HttpEntity<>(body, headers), Map.class);
    }
}
