package com.aishorts.engine.drafts;

import com.aishorts.engine.claude.ClaudeApiException;
import com.aishorts.engine.claude.ClaudeConfig;
import com.aishorts.engine.claude.ClaudeHttpException;
import com.aishorts.engine.claude.ClaudeStreamingClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Container;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ServerToolUsage;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Usage;
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
import java.util.TreeMap;

/**
 * StoryDraftGenerator real: una pasada contra la Messages API de Claude,
 * opcionalmente con la herramienta de búsqueda web del servidor, siempre
 * con streaming (ClaudeStreamingClient, sobre el SDK oficial) para que la
 * conexión no quede minutos sin tráfico.
 *
 * - pause_turn: el contenido del asistente se reenvía INTACTO (incluidos
 *   encrypted_content y encrypted_index, que la API exige sin cambios),
 *   acumulado en un único mensaje del asistente, hasta un tope de
 *   continuaciones. Los bloques se guardan como JSON genérico (con todos
 *   sus campos) y se reenvían así, no como tipos del SDK.
 * - El tool de búsqueda web también va como JSON: su versión es
 *   configurable (y tiene fallback), así que no se ata a una clase del SDK.
 * - researchedUrls: se juntan las URLs de los web_search_result (en
 *   cualquier nivel del contenido, porque con filtrado dinámico llegan
 *   anidados) y las de las citas web_search_result_location.
 * - Si el modelo rechaza la versión de la herramienta (por ejemplo, porque
 *   no soporta llamadas programáticas), reintenta UNA vez con la versión de
 *   fallback y allowed_callers ["direct"].
 * - Si la búsqueda web está deshabilitada en la organización, falla con un
 *   mensaje claro (502) en vez de un 400 crudo.
 */
public final class ClaudeStoryDraftGenerator implements StoryDraftGenerator, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ClaudeStoryDraftGenerator.class);
    private static final int MAX_ERROR_BODY_CHARS = 1_000;

    private final ClaudeStreamingClient client;
    private final ClaudeConfig config;
    private final DraftsProperties properties;

    public ClaudeStoryDraftGenerator(ClaudeStreamingClient client, ClaudeConfig config, DraftsProperties properties) {
        this.client = client;
        this.config = config;
        this.properties = properties;
    }

    /**
     * El generador tal como se usa en producción: cliente en HTTP/1.1 con
     * timeout de inactividad (app.drafts.read-timeout-seconds). Una llamada
     * con búsqueda web pasa minutos trabajando, y en una red con inspección
     * TLS se observó que la conexión se cortaba a mitad de camino.
     */
    public static ClaudeStoryDraftGenerator create(ClaudeConfig config, DraftsProperties properties) {
        ClaudeStreamingClient client = new ClaudeStreamingClient(config, Duration.ofSeconds(properties.readTimeoutSeconds()));
        return new ClaudeStoryDraftGenerator(client, config, properties);
    }

    @Override
    public void close() {
        client.close();
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
        List<Object> assistantBlocks = new ArrayList<>();   // para leer (texto, URLs)
        List<JsonValue> resendBlocks = new ArrayList<>();   // los mismos, exactos, para reenviar
        DraftUsage usage = DraftUsage.ZERO;
        String containerId = null;
        String stopReason;

        // app.drafts.max-tokens: el borrador necesita más que el resto (thinking + JSON de 6 escenas)
        long maxTokens = properties.maxTokens() > 0 ? properties.maxTokens() : config.maxTokens();
        int calls = 0;

        for (int continuation = 0; ; continuation++) {
            MessageCreateParams.Builder params = MessageCreateParams.builder()
                    .model(config.model())
                    .maxTokens(maxTokens)
                    .system(request.systemPrompt())
                    .addUserMessage(request.userMessage());
            if (!resendBlocks.isEmpty()) {
                params.addMessage(MessageParam.builder()
                        .role(MessageParam.Role.ASSISTANT)
                        .content(JsonValue.from(List.copyOf(resendBlocks)))
                        .build());
            }
            if (tool != null) {
                params.putAdditionalBodyProperty("tools", JsonValue.from(List.of(tool)));
            }
            if (containerId != null) {
                params.container(containerId);
            }

            Message response = client.stream(params.build());
            calls++;
            for (ContentBlock block : response.content()) {
                assistantBlocks.add(ClaudeStreamingClient.toMap(block));
                resendBlocks.add(ClaudeStreamingClient.toJsonValue(block));
            }
            usage = usage.plus(usageOf(response.usage()));
            containerId = response.container().map(Container::id).orElse(containerId);
            stopReason = response.stopReason().map(StopReason::asString).orElse(null);

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

        StringBuilder text = new StringBuilder();
        Set<String> urls = new LinkedHashSet<>();
        Map<String, Integer> blockCounts = new TreeMap<>();
        for (Object block : assistantBlocks) {
            if (block instanceof Map<?, ?> map) {
                blockCounts.merge(String.valueOf(map.get("type")), 1, Integer::sum);
                if ("text".equals(map.get("type")) && map.get("text") instanceof String t) {
                    text.append(t);
                }
            }
            collectUrls(block, urls);
        }
        log.info("Pasada {} terminada: stop_reason={}, llamadas={}, bloques={}, max_tokens={}, usage={}",
                request.mode(), stopReason, calls, blockCounts, maxTokens, usage);
        return new GeneratorResult(text.toString(), new ArrayList<>(urls), usage, stopReason, calls, blockCounts);
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

    private static DraftUsage usageOf(Usage usage) {
        long searches = usage.serverToolUse().map(ServerToolUsage::webSearchRequests).orElse(0L);
        return new DraftUsage(usage.inputTokens(), usage.outputTokens(), searches);
    }

    static boolean isWebSearchDisabled(String body) {
        String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
        return lower.contains("web search") && (lower.contains("not enabled") || lower.contains("disabled"));
    }

    static boolean isToolVersionRejected(String body, String toolVersion) {
        String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
        return lower.contains("allowed_callers") || (toolVersion != null && lower.contains(toolVersion.toLowerCase(Locale.ROOT)));
    }

    /**
     * 502 con la cadena de causas a la vista (por ejemplo "Error de red ... |
     * causa: java.io.IOException: connection reset"), y el stack trace
     * completo en el log: un "Error de red" a secas no permite diagnosticar.
     */
    private static DraftException toDraftException(ClaudeApiException e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (message.length() > MAX_ERROR_BODY_CHARS) {
            message = message.substring(0, MAX_ERROR_BODY_CHARS) + "…";
        }
        String causes = causeChain(e);
        log.error("Falló la llamada a Claude: {}{}", message, causes, e);
        return new DraftException(502, "Falló la llamada a Claude: " + message + causes, e);
    }

    /** " | causa: Clase: mensaje" por cada causa anidada, o "" si no hay. */
    static String causeChain(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable cause = e.getCause();
        for (int depth = 0; cause != null && cause != e && depth < 5; depth++) {
            sb.append(" | causa: ").append(cause.getClass().getName());
            if (cause.getMessage() != null) {
                sb.append(": ").append(cause.getMessage());
            }
            e = cause;
            cause = cause.getCause();
        }
        return sb.toString();
    }
}
