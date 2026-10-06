package com.aishorts.engine.drafts;

import com.aishorts.engine.claude.ClaudeApiException;
import com.aishorts.engine.claude.ClaudeHttpException;
import com.aishorts.engine.claude.ClaudeMessagesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * StoryDraftGenerator real: una pasada contra la Messages API de Claude,
 * opcionalmente con la herramienta de búsqueda web del servidor.
 *
 * - pause_turn: el contenido del asistente se reenvía INTACTO (incluidos
 *   encrypted_content y encrypted_index, que la API exige sin cambios),
 *   acumulado en un único mensaje del asistente, hasta un tope de
 *   continuaciones.
 * - researchedUrls: se juntan las URLs de los web_search_result (en
 *   cualquier nivel del contenido, porque con filtrado dinámico llegan
 *   anidados) y las de las citas web_search_result_location.
 * - Si el modelo rechaza la versión de la herramienta (por ejemplo, porque
 *   no soporta llamadas programáticas), reintenta UNA vez con la versión de
 *   fallback y allowed_callers ["direct"].
 * - Si la búsqueda web está deshabilitada en la organización, falla con un
 *   mensaje claro (502) en vez de un 400 crudo.
 */
public final class ClaudeStoryDraftGenerator implements StoryDraftGenerator {

    private static final Logger log = LoggerFactory.getLogger(ClaudeStoryDraftGenerator.class);
    private static final int MAX_ERROR_BODY_CHARS = 1_000;

    private final ClaudeMessagesClient client;
    private final DraftsProperties properties;

    public ClaudeStoryDraftGenerator(ClaudeMessagesClient client, DraftsProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public GeneratorResult generate(GeneratorRequest request) {
        DraftsProperties.WebSearch webSearch = properties.webSearch();
        Map<String, Object> tool = request.webSearch()
                ? webSearchTool(webSearch.toolVersion(), request.maxSearchUses(), webSearch.allowedCallers())
                : null;
        try {
            return run(request, tool);
        } catch (ClaudeHttpException e) {
            if (tool != null && e.status() == 400 && isWebSearchDisabled(e.responseBody())) {
                throw new DraftException(502, "La búsqueda web no está habilitada en la organización de Claude. "
                        + "Un administrador debe activarla en Claude Console (Settings > Capabilities), o arranca con "
                        + "CLAUDE_WEB_SEARCH_ENABLED=false (las fuentes quedarán SIN_VERIFICAR).", e);
            }
            String fallback = webSearch.fallbackToolVersion();
            if (tool != null && e.status() == 400 && fallback != null && !fallback.isBlank()
                    && !fallback.equals(webSearch.toolVersion()) && isToolVersionRejected(e.responseBody(), webSearch.toolVersion())) {
                log.info("El modelo rechazó la herramienta {}; reintentando con {} y allowed_callers [direct].",
                        webSearch.toolVersion(), fallback);
                try {
                    return run(request, webSearchTool(fallback, request.maxSearchUses(), List.of("direct")));
                } catch (ClaudeApiException retryError) {
                    throw toDraftException(retryError);
                }
            }
            throw toDraftException(e);
        } catch (ClaudeApiException e) {
            throw toDraftException(e);
        }
    }

    private GeneratorResult run(GeneratorRequest request, Map<String, Object> tool) {
        Map<String, Object> userMessage = Map.of("role", "user", "content", request.userMessage());
        List<Object> assistantBlocks = new ArrayList<>();
        DraftUsage usage = DraftUsage.ZERO;
        String containerId = null;
        String stopReason;
        Duration timeout = Duration.ofSeconds(properties.readTimeoutSeconds());

        for (int continuation = 0; ; continuation++) {
            List<Object> messages = new ArrayList<>();
            messages.add(userMessage);
            if (!assistantBlocks.isEmpty()) {
                messages.add(Map.of("role", "assistant", "content", List.copyOf(assistantBlocks)));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("system", request.systemPrompt());
            body.put("messages", messages);
            if (tool != null) {
                body.put("tools", List.of(tool));
            }
            if (containerId != null) {
                body.put("container", containerId);
            }

            Map<String, Object> response = client.createMessage(body, timeout);
            if (response.get("content") instanceof List<?> content) {
                assistantBlocks.addAll(content);
            }
            usage = usage.plus(usageOf(response.get("usage")));
            if (response.get("container") instanceof Map<?, ?> container && container.get("id") instanceof String id) {
                containerId = id;
            }
            stopReason = response.get("stop_reason") instanceof String s ? s : null;

            if (!"pause_turn".equals(stopReason)) {
                break;
            }
            if (continuation >= properties.maxPauseContinuations()) {
                throw new DraftException(502, "Claude pausó el turno (pause_turn) más de "
                        + properties.maxPauseContinuations() + " veces; se abandonó la generación.");
            }
            log.info("Claude devolvió pause_turn; continuando ({}/{}).", continuation + 1, properties.maxPauseContinuations());
        }

        if ("refusal".equals(stopReason)) {
            throw new DraftException(502, "Claude se negó a generar el contenido (stop_reason=refusal).");
        }
        if ("max_tokens".equals(stopReason)) {
            log.info("La respuesta de Claude se cortó por max_tokens; el JSON probablemente quedó incompleto.");
        }

        StringBuilder text = new StringBuilder();
        Set<String> urls = new LinkedHashSet<>();
        for (Object block : assistantBlocks) {
            if (block instanceof Map<?, ?> map && "text".equals(map.get("type")) && map.get("text") instanceof String t) {
                text.append(t);
            }
            collectUrls(block, urls);
        }
        return new GeneratorResult(text.toString(), new ArrayList<>(urls), usage, stopReason);
    }

    /** Recorre cualquier estructura de bloques buscando resultados de búsqueda y citas web. */
    private static void collectUrls(Object node, Set<String> urls) {
        if (node instanceof Map<?, ?> map) {
            Object type = map.get("type");
            if (("web_search_result".equals(type) || "web_search_result_location".equals(type))
                    && map.get("url") instanceof String url && !url.isBlank()) {
                urls.add(url);
            }
            for (Object value : map.values()) {
                collectUrls(value, urls);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                collectUrls(item, urls);
            }
        }
    }

    private static Map<String, Object> webSearchTool(String version, int maxUses, List<String> allowedCallers) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", version);
        tool.put("name", "web_search");
        if (maxUses > 0) {
            tool.put("max_uses", maxUses);
        }
        if (allowedCallers != null && !allowedCallers.isEmpty()) {
            tool.put("allowed_callers", allowedCallers);
        }
        return tool;
    }

    private static DraftUsage usageOf(Object usage) {
        if (!(usage instanceof Map<?, ?> map)) {
            return DraftUsage.ZERO;
        }
        long searches = 0;
        if (map.get("server_tool_use") instanceof Map<?, ?> serverToolUse) {
            searches = longOf(serverToolUse.get("web_search_requests"));
        }
        return new DraftUsage(longOf(map.get("input_tokens")), longOf(map.get("output_tokens")), searches);
    }

    private static long longOf(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    static boolean isWebSearchDisabled(String body) {
        String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
        return lower.contains("web search") && (lower.contains("not enabled") || lower.contains("disabled"));
    }

    static boolean isToolVersionRejected(String body, String toolVersion) {
        String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
        return lower.contains("allowed_callers") || (toolVersion != null && lower.contains(toolVersion.toLowerCase(Locale.ROOT)));
    }

    private static DraftException toDraftException(ClaudeApiException e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (message.length() > MAX_ERROR_BODY_CHARS) {
            message = message.substring(0, MAX_ERROR_BODY_CHARS) + "…";
        }
        return new DraftException(502, "Falló la llamada a Claude: " + message, e);
    }
}
