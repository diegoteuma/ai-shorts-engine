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
        double wordsPerSecond,
        int minTotalWords,
        int maxTotalWords,
        double minSceneSeconds,
        double maxSceneSeconds,
        Map<String, WordRange> sceneWordRanges,
        String styleAnchor,
        List<String> forbiddenVisualTerms,
        List<String> negativePromptTerms,
        List<String> tierADomains,
        List<String> tierBDomains,
        List<String> orientationOnlyDomains
) {

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
        tierADomains = tierADomains != null ? tierADomains : List.of();
        tierBDomains = tierBDomains != null ? tierBDomains : List.of();
        orientationOnlyDomains = orientationOnlyDomains != null ? orientationOnlyDomains : List.of();
    }
}
