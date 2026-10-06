package com.aishorts.engine.drafts;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DraftPromptBuilderTest {

    @Test
    void missingRulesFile_failsWithAClearMessage() {
        assertThatThrownBy(() -> DraftPromptBuilder.loadRules("prompts/no-existe.md"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prompts/no-existe.md")
                .hasMessageContaining("app.drafts.rules-resource");
    }

    @Test
    void systemPrompt_isTheRulesFileVerbatimPlusTheExecutionContext() {
        String rules = DraftPromptBuilder.loadRules(DraftFixtures.properties().rulesResource());
        DraftPromptBuilder builder = new DraftPromptBuilder(rules, new ObjectMapper());

        String prompt = builder.systemPrompt("BORRADOR", LocalDate.of(2026, 10, 6),
                List.of(new ExistingStory("tunguska", "La explosión", "Tunguska 1908")), true, 10);

        assertThat(prompt).startsWith(rules.strip());
        assertThat(prompt).contains("Modo: BORRADOR", "Fecha de hoy: 2026-10-06", "máximo 10 búsquedas",
                "{\"id\":\"tunguska\",\"title\":\"La explosión\",\"topic\":\"Tunguska 1908\"}");
    }

    @Test
    void slugs_andFreeIds_stayValidAndWithin40Chars() {
        assertThat(DraftText.slugify("¡Erupción del Tambora (1815)!")).isEqualTo("erupcion-del-tambora-1815");
        assertThat(DraftText.slugify("   ")).isEqualTo("historia");
        String longSlug = DraftText.slugify("una historia con un titulo larguisimo que no entra en cuarenta");
        assertThat(DraftText.isValidId(longSlug)).isTrue();

        String base = "a".repeat(40);
        String free = StoryDraftService.freeId(base, Set.of(base));
        assertThat(free).hasSize(40).endsWith("-2");
        assertThat(DraftText.isValidId(free)).isTrue();
        assertThat(StoryDraftService.freeId("tunguska", Set.of("tunguska", "tunguska-2"))).isEqualTo("tunguska-3");
    }
}
