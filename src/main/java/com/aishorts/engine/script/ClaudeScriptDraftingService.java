package com.aishorts.engine.script;

import com.aishorts.engine.claude.ClaudeApiException;
import com.aishorts.engine.claude.ClaudeConfig;
import com.aishorts.engine.claude.ClaudeMessagesClient;
import com.aishorts.engine.domain.SceneRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Llama a la API de Claude (vía ClaudeMessagesClient) para redactar el
 * borrador de las 6 escenas.
 *
 * El modelo debe responder SOLO con un array JSON de 6 objetos; el parseo es
 * estricto a propósito (falla con ScriptDraftingException, no intenta
 * adivinar) porque un guion mal formado no debería llegar silenciosamente
 * incompleto a tu revisión en la puerta 1.
 */
public final class ClaudeScriptDraftingService implements ScriptDraftingService {

    private static final SceneRole[] EXPECTED_ORDER = {
            SceneRole.GANCHO, SceneRole.EXPLICACION, SceneRole.CONTEXTO,
            SceneRole.GIRO, SceneRole.CONSECUENCIA, SceneRole.CIERRE
    };

    private final ClaudeMessagesClient client;
    private final ObjectMapper objectMapper;

    public ClaudeScriptDraftingService(ClaudeConfig config, ObjectMapper objectMapper) {
        this.client = new ClaudeMessagesClient(config, objectMapper);
        this.objectMapper = objectMapper;
    }

    @Override
    public List<SceneDraft> draftScenes(StoryBrief brief) throws ScriptDraftingException {
        String systemPrompt = buildSystemPrompt(brief);
        String userMessage = buildUserMessage(brief);

        String rawText;
        try {
            rawText = client.sendMessage(systemPrompt, userMessage);
        } catch (ClaudeApiException e) {
            throw new ScriptDraftingException("Error llamando a la API de Claude para el guion", e);
        }
        return parseScenes(rawText, brief);
    }

    @SuppressWarnings("unchecked")
    private List<SceneDraft> parseScenes(String rawText, StoryBrief brief) {
        String jsonText = ClaudeMessagesClient.stripMarkdownFence(rawText.trim());
        Object parsed;
        try {
            parsed = objectMapper.readValue(jsonText, Object.class);
        } catch (JsonProcessingException e) {
            throw new ScriptDraftingException(
                    "No se pudo parsear el guion devuelto por Claude como JSON. Texto crudo:\n" + rawText, e);
        }
        if (!(parsed instanceof List<?> list)) {
            throw new ScriptDraftingException("Se esperaba un array JSON de escenas. Texto crudo:\n" + rawText);
        }
        if (list.size() != EXPECTED_ORDER.length) {
            throw new ScriptDraftingException(
                    "Se esperaban " + EXPECTED_ORDER.length + " escenas y llegaron " + list.size()
                            + ". Texto crudo:\n" + rawText);
        }

        List<SceneDraft> drafts = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> entry = (Map<String, Object>) list.get(i);
            String roleText = String.valueOf(entry.get("role"));
            SceneRole role;
            try {
                role = SceneRole.valueOf(roleText.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ScriptDraftingException("Rol de escena desconocido: '" + roleText + "' en la posición " + i);
            }
            if (role != EXPECTED_ORDER[i]) {
                throw new ScriptDraftingException(
                        "Las escenas deben venir en orden " + List.of(EXPECTED_ORDER) + ", pero en la posición "
                                + i + " llegó " + role + ".");
            }
            String narrationText = String.valueOf(entry.get("narrationText"));
            String visualPrompt = String.valueOf(entry.get("visualPrompt"));
            drafts.add(new SceneDraft(role, narrationText, visualPrompt));
        }
        return drafts;
    }

    private String buildSystemPrompt(StoryBrief brief) {
        StringBuilder sb = new StringBuilder();
        sb.append("Sos el guionista de un microdocumental para YouTube Shorts, en español neutro ")
                .append("(entendible en toda Latinoamérica, sin modismos regionales), narrado sin presentador en cámara.\n\n");
        sb.append("El formato tiene una estructura fija de 6 escenas, en este orden exacto:\n");
        sb.append("1. GANCHO: un hecho impactante y comprensible, sin revelar el nombre del caso todavía.\n");
        sb.append("2. EXPLICACION: cómo/por qué pasó lo del gancho.\n");
        sb.append("3. CONTEXTO: recién acá se revela el nombre/lugar/fecha del caso real.\n");
        sb.append("4. GIRO: se anuncia explícitamente que empieza la especulación (cambiar una condición).\n");
        sb.append("5. CONSECUENCIA: la consecuencia hipotética, científicamente plausible, sin cifras inventadas de víctimas o daños.\n");
        sb.append("6. CIERRE: una pregunta o frase de cierre breve.\n\n");
        sb.append("Restricciones del formato:\n");
        for (String constraint : brief.constraints()) {
            sb.append("- ").append(constraint).append('\n');
        }
        sb.append('\n');
        sb.append("Respondé ÚNICAMENTE con un array JSON de 6 objetos, en el orden de arriba, sin texto antes ni después, ")
                .append("sin bloque de código markdown. Cada objeto tiene exactamente estas claves: ")
                .append("\"role\" (uno de GANCHO, EXPLICACION, CONTEXTO, GIRO, CONSECUENCIA, CIERRE), ")
                .append("\"narrationText\" (el texto narrado, en español neutro) y ")
                .append("\"visualPrompt\" (una descripción de la escena para un generador de video/imagen, en inglés o español, ")
                .append("describiendo composición, acción y estilo, sin mencionar marcas ni texto en pantalla salvo que el guion lo pida).");
        return sb.toString();
    }

    private String buildUserMessage(StoryBrief brief) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tema: ").append(brief.topic()).append('\n');
        sb.append("Título elegido: ").append(brief.title()).append('\n');
        sb.append("Hechos núcleo (REAL, ya verificados con fuentes — no agregues datos que no estén acá):\n");
        for (String fact : brief.coreFacts()) {
            sb.append("- ").append(fact).append('\n');
        }
        long targetSeconds = brief.durationBudget().max().toSeconds();
        sb.append('\n').append("Duración objetivo total del Short: entre ")
                .append(brief.durationBudget().min().toSeconds()).append(" y ").append(targetSeconds)
                .append(" segundos entre las 6 escenas. Escribí narraciones breves, acordes a ese total ")
                .append("(pensá en un ritmo de lectura de unas 2,6 palabras por segundo en español neutro).");
        return sb.toString();
    }
}
