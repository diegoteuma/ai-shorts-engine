package com.aishorts.engine.drafts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * Arma los prompts del generador: el system prompt es SIEMPRE el archivo
 * de reglas (prompts/story-generator-rules.md, sin modificar) más un bloque
 * de contexto de ejecución (modo, fecha, búsqueda web, existingStories).
 * El mensaje del usuario lleva el tema, el feedback del revisor humano y,
 * en el reintento, el JSON anterior con sus violaciones.
 */
public final class DraftPromptBuilder {

    private final String rules;
    private final ObjectMapper objectMapper;

    public DraftPromptBuilder(String rules, ObjectMapper objectMapper) {
        this.rules = rules;
        this.objectMapper = objectMapper;
    }

    /** Lee el archivo de reglas del classpath; si falta o está vacío, el arranque falla con un mensaje claro. */
    public static String loadRules(String classpathLocation) {
        String location = classpathLocation.startsWith("classpath:") ? classpathLocation.substring("classpath:".length()) : classpathLocation;
        ClassPathResource resource = new ClassPathResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("No se encontró el archivo de reglas del generador de historias en el classpath: '"
                    + location + "'. Debe existir src/main/resources/" + location + " (app.drafts.rules-resource).");
        }
        try (InputStream in = resource.getInputStream()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (text.isBlank()) {
                throw new IllegalStateException("El archivo de reglas '" + location + "' está vacío.");
            }
            return text;
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer el archivo de reglas '" + location + "'.", e);
        }
    }

    public String systemPrompt(String mode, LocalDate today, Collection<ExistingStory> existingStories,
                               boolean webSearch, int maxSearchUses) {
        StringBuilder sb = new StringBuilder(rules.strip());
        sb.append("\n\n---\n\n## Contexto de ejecución (lo agrega el sistema)\n\n");
        sb.append("- Modo: ").append(mode).append('\n');
        sb.append("- Fecha de hoy: ").append(today).append(" (úsala como fecha de consulta de las fuentes).\n");
        if (webSearch) {
            sb.append("- Tienes búsqueda web en este turno (máximo ").append(maxSearchUses)
                    .append(" búsquedas). Cita solo URLs que aparezcan en tus resultados de búsqueda.\n");
        } else {
            sb.append("- NO tienes búsqueda web en este turno. No agregues URLs nuevas: conserva solo las que ya ")
                    .append("consultaste; si no puedes respaldar una afirmación, márcala SIN_VERIFICAR con \"url\": null.\n");
        }
        sb.append("- existingStories (no repitas ninguno de estos temas ni sus what-if):\n");
        sb.append(toJson(existingStories)).append('\n');
        return sb.toString();
    }

    public String proposalsUserMessage(String focus) {
        StringBuilder sb = new StringBuilder("MODO: PROPUESTAS\n");
        if (focus != null && !focus.isBlank()) {
            sb.append("Enfoque pedido por el editor: ").append(focus.strip()).append('\n');
        }
        sb.append("Devuelve solo el JSON del modo PROPUESTAS, con exactamente 5 candidatos.");
        return sb.toString();
    }

    public String draftUserMessage(String topic, StoryDraft rejectedDraft) {
        StringBuilder sb = new StringBuilder("MODO: BORRADOR\n");
        sb.append("Tema elegido: ").append(topic.strip()).append('\n');
        appendReviewerFeedback(sb, rejectedDraft);
        sb.append("Devuelve solo el JSON del formato de la sección 10.");
        return sb.toString();
    }

    public String retryUserMessage(String topic, StoryDraft rejectedDraft, String previousResponse, List<Violation> hardViolations) {
        StringBuilder sb = new StringBuilder("MODO: BORRADOR (corrección)\n");
        sb.append("Tema elegido: ").append(topic.strip()).append('\n');
        appendReviewerFeedback(sb, rejectedDraft);
        sb.append("\nTu respuesta anterior no pasó la validación automática del sistema. Violaciones DURAS:\n");
        for (Violation violation : hardViolations) {
            sb.append("- [").append(violation.code()).append("] ").append(violation.message()).append('\n');
        }
        sb.append("\nTu respuesta anterior:\n").append(previousResponse == null ? "(vacía)" : previousResponse.strip()).append('\n');
        sb.append("\nCorrige TODAS las violaciones y devuelve el JSON completo del formato de la sección 10. ")
                .append("Mantén las fuentes y URLs que ya consultaste; no inventes URLs nuevas.");
        return sb.toString();
    }

    private static void appendReviewerFeedback(StringBuilder sb, StoryDraft rejectedDraft) {
        if (rejectedDraft != null && rejectedDraft.rejectionNote != null) {
            sb.append("\nUn revisor humano RECHAZÓ un borrador anterior (id '").append(rejectedDraft.id)
                    .append("', título \"").append(rejectedDraft.title).append("\") con esta nota. Tenla en cuenta:\n")
                    .append(rejectedDraft.rejectionNote.strip()).append("\n\n");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar existingStories", e);
        }
    }
}
