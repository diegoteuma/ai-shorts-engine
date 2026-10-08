package com.aishorts.engine.drafts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Borrador de historia generado por Claude, tal como se guarda en
 * data/drafts/&lt;id&gt;.json y se muestra en la PUERTA 0.
 *
 * A propósito NO es una Story: tiene mucho más que lo que el pipeline
 * necesita (afirmaciones, fuentes, what-if, violaciones). Solo al aprobarlo
 * se crea una Story con los campos del pipeline (ver StoryDraftService).
 *
 * Es un objeto de datos mutable (campos públicos) porque el normalizador y
 * las ediciones de la PUERTA 0 lo modifican en el lugar antes de
 * revalidarlo; las reglas viven en DraftValidator, no acá.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoryDraft {

    public String id;
    public String topic;
    public String title;
    public DraftStatus status;
    public int attempts;
    public String requestedTopic;
    public String rejectedDraftId;
    public String reviewerFeedback;
    public String createdAt;
    /** Última edición manual (PATCH /story-drafts/{id}); null si nunca se editó. */
    public String editedAt;

    public List<DraftScene> scenes = new ArrayList<>();
    public List<Claim> claims = new ArrayList<>();
    public WhatIf whatIf;
    public DraftChecks checks = new DraftChecks();
    public List<String> risks = new ArrayList<>();

    public List<Violation> violations = new ArrayList<>();
    public List<String> researchedUrls = new ArrayList<>();
    public DraftUsage usage = DraftUsage.ZERO;
    /** Texto crudo de la última respuesta de Claude, solo cuando no se pudo leer como JSON (para diagnosticar). */
    public String unparsedResponse;

    public String rejectionNote;
    public String decidedAt;
    public String approvedStoryId;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class DraftScene {
        public String id;
        public String role;
        public Integer order;
        public String narrationText;
        public String visualPrompt;
        public List<String> visualConstraints = new ArrayList<>();
        public List<String> claimIds = new ArrayList<>();
        public int words;
        public double estimatedSeconds;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Claim {
        public String id;
        public String type;
        public String text;
        public String value;
        public String range;
        public String status;
        public String confidence;
        public Boolean disputed;
        public List<Source> sources = new ArrayList<>();
        public String note;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Source {
        public String title;
        public String publisher;
        public String url;
        public String accessed;
        public String tier;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class WhatIf {
        public String premise;
        public List<WhatIfParameter> parametersFromReal = new ArrayList<>();
        public List<String> assumptions = new ArrayList<>();
        public String limits;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class WhatIfParameter {
        public String name;
        public String value;
        public String claimId;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class DraftChecks {
        public int totalWords;
        public double estimatedSeconds;
        public boolean allFactsHaveClaims;
        public boolean sourcesVerified;
        public List<String> violations = new ArrayList<>();
    }
}
