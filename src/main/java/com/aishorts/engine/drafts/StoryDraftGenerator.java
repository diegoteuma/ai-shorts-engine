package com.aishorts.engine.drafts;

import java.util.List;

/**
 * Puerto hacia el LLM que escribe borradores y propuestas. La
 * implementación real (ClaudeStoryDraftGenerator) llama a la API de Claude
 * con búsqueda web; los tests la reemplazan por un fake con @Primary, así
 * que nada de lo que está detrás de esta interfaz se ejecuta sin red real.
 *
 * Una llamada a generate es una "pasada" completa del modelo (incluidas
 * las continuaciones por pause_turn). Los reintentos por validación los
 * decide StoryDraftService, no el generador.
 */
public interface StoryDraftGenerator {

    GeneratorResult generate(GeneratorRequest request) throws DraftException;

    enum Mode { PROPOSALS, DRAFT, DRAFT_RETRY }

    /**
     * @param webSearch     si se ofrece la herramienta de búsqueda web en esta pasada
     * @param maxSearchUses tope de búsquedas (max_uses) cuando webSearch es true
     */
    record GeneratorRequest(Mode mode, String systemPrompt, String userMessage, boolean webSearch, int maxSearchUses) {
    }

    /**
     * @param text           texto concatenado de todos los bloques de texto del asistente
     * @param researchedUrls URLs que de verdad aparecieron en web_search_tool_result o en citas
     * @param stopReason     stop_reason de la última respuesta
     */
    record GeneratorResult(String text, List<String> researchedUrls, DraftUsage usage, String stopReason) {
        public GeneratorResult {
            researchedUrls = researchedUrls != null ? List.copyOf(researchedUrls) : List.of();
            usage = usage != null ? usage : DraftUsage.ZERO;
        }
    }
}
