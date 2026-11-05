package com.aishorts.engine.drafts;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Configuración del generador de historias (app.drafts.* en
 * application.yml, cada valor con su variable de entorno y su default).
 * Ver ese archivo para la documentación de cada clave.
 */
@ConfigurationProperties(prefix = "app.drafts")
public record DraftsProperties(
        String dir,
        String rulesResource,
        WebSearch webSearch,
        int readTimeoutSeconds,
        int maxPauseContinuations,
        int maxTokens,
        double wordsPerSecond,
        int minTotalWords,
        int maxTotalWords,
        double minSceneSeconds,
        double maxSceneSeconds,
        Map<String, WordRange> sceneWordRanges,
        String styleAnchor,
        List<String> forbiddenVisualTerms,
        List<String> negativePromptTerms,
        List<String> slowRevealTerms,
        List<String> hookAftermathTerms,
        List<String> absoluteTerms,
        List<String> calmVisualTerms,
        List<String> humanScaleTerms,
        List<String> tierADomains,
        List<String> tierBDomains,
        List<String> orientationOnlyDomains,
        Pricing pricing,
        boolean promptCache,
        String effort
) {

    /** Niveles de output_config.effort que acepta la API (docs: build-with-claude/effort). */
    public static final List<String> EFFORT_LEVELS = List.of("low", "medium", "high", "xhigh", "max");

    /**
     * Precios en USD para estimar el costo de un borrador desde su usage:
     * por millón de tokens (entrada sin caché, salida, escritura y lectura
     * de caché) y por cada 1.000 búsquedas web.
     */
    public record Pricing(
            double inputPerMtok,
            double outputPerMtok,
            double searchPer1000,
            double cacheWritePerMtok,
            double cacheReadPerMtok
    ) {
        public static final Pricing NONE = new Pricing(0, 0, 0, 0, 0);
    }

    public record WebSearch(
            boolean enabled,
            String toolVersion,
            List<String> allowedCallers,
            String fallbackToolVersion,
            int maxUsesDraft,
            int maxUsesProposals
    ) {
        public WebSearch {
            allowedCallers = allowedCallers != null ? allowedCallers : List.of();
        }
    }

    public record WordRange(int min, int max) {
    }

    public DraftsProperties {
        sceneWordRanges = sceneWordRanges != null ? sceneWordRanges : Map.of();
        forbiddenVisualTerms = forbiddenVisualTerms != null ? forbiddenVisualTerms : List.of();
        negativePromptTerms = negativePromptTerms != null ? negativePromptTerms : List.of();
        slowRevealTerms = slowRevealTerms != null ? slowRevealTerms : List.of();
        hookAftermathTerms = hookAftermathTerms != null ? hookAftermathTerms : List.of();
        absoluteTerms = absoluteTerms != null ? absoluteTerms : List.of();
        calmVisualTerms = calmVisualTerms != null ? calmVisualTerms : List.of();
        humanScaleTerms = humanScaleTerms != null ? humanScaleTerms : List.of();
        tierADomains = tierADomains != null ? tierADomains : List.of();
        tierBDomains = tierBDomains != null ? tierBDomains : List.of();
        orientationOnlyDomains = orientationOnlyDomains != null ? orientationOnlyDomains : List.of();
        pricing = pricing != null ? pricing : Pricing.NONE;
        // vacío = no se manda output_config.effort (el default del modelo, como hasta ahora)
        effort = effort == null || effort.isBlank() ? null : effort.trim().toLowerCase(java.util.Locale.ROOT);
        if (effort != null && !EFFORT_LEVELS.contains(effort)) {
            throw new IllegalArgumentException("app.drafts.effort (CLAUDE_DRAFT_EFFORT) es '" + effort
                    + "'; los valores válidos son " + EFFORT_LEVELS + " o vacío (default del modelo).");
        }
    }
}
