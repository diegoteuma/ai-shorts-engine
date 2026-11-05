package com.aishorts.engine.drafts;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DraftCostTest {

    private static final DraftsProperties.Pricing SONNET_5_5 = DraftFixtures.properties().pricing();

    @Test
    void defaults_areTheSonnet55PricesFromTheDocs() {
        assertThat(SONNET_5_5).isEqualTo(new DraftsProperties.Pricing(2, 10, 10, 2.50, 0.10));
    }

    @Test
    void reproducesTheMeasuredDrafts_withoutCache() {
        // usage real de los borradores medidos (tabla del pedido): ~1,35 y ~2,07 USD
        assertThat(DraftCost.estimateUsd(new DraftUsage(331_207, 58_441, 10), SONNET_5_5)).isEqualTo(1.3468);
        assertThat(DraftCost.estimateUsd(new DraftUsage(721_939, 52_169, 10), SONNET_5_5)).isEqualTo(2.0656);
    }

    @Test
    void eachComponent_isPricedSeparately() {
        assertThat(DraftCost.estimateUsd(new DraftUsage(1_000_000, 0, 0), SONNET_5_5)).isEqualTo(2.0);
        assertThat(DraftCost.estimateUsd(new DraftUsage(0, 1_000_000, 0), SONNET_5_5)).isEqualTo(10.0);
        assertThat(DraftCost.estimateUsd(new DraftUsage(0, 0, 4), SONNET_5_5)).isEqualTo(0.04);
        assertThat(DraftCost.estimateUsd(new DraftUsage(0, 0, 0, 1_000_000, 0), SONNET_5_5)).isEqualTo(2.5);
        assertThat(DraftCost.estimateUsd(new DraftUsage(0, 0, 0, 0, 1_000_000), SONNET_5_5)).isEqualTo(0.1);
    }

    @Test
    void cachedInput_isCheaperThanTheSameInputWithoutCache() {
        // Mismos 720K tokens de entrada: 40K sin caché, 60K escritos y 620K leídos de caché.
        double withCache = DraftCost.estimateUsd(new DraftUsage(40_000, 52_169, 4, 60_000, 620_000), SONNET_5_5);
        double withoutCache = DraftCost.estimateUsd(new DraftUsage(720_000, 52_169, 4), SONNET_5_5);
        assertThat(withCache).isEqualTo(0.8537);   // 0,08 + 0,52169 + 0,04 + 0,15 + 0,062
        assertThat(withoutCache).isEqualTo(2.0017);
    }

    @Test
    void nullsAndZero_costNothing() {
        assertThat(DraftCost.estimateUsd(null, SONNET_5_5)).isZero();
        assertThat(DraftCost.estimateUsd(DraftUsage.ZERO, SONNET_5_5)).isZero();
        assertThat(DraftCost.estimateUsd(new DraftUsage(1, 1, 1), null)).isZero();
    }

    @Test
    void usagePlus_addsTheCacheFieldsToo() {
        DraftUsage sum = new DraftUsage(10, 20, 1, 300, 4000).plus(new DraftUsage(1, 2, 3)).plus(null);
        assertThat(sum).isEqualTo(new DraftUsage(11, 22, 4, 300, 4000));
    }

    @Test
    void pricesAreConfigurable_throughEnvironmentVariables() {
        DraftsProperties.Pricing custom = DraftFixtures.properties(Map.of(
                "CLAUDE_PRICE_INPUT_PER_MTOK", "3", "CLAUDE_PRICE_OUTPUT_PER_MTOK", "15",
                "CLAUDE_PRICE_SEARCH_PER_1000", "10", "CLAUDE_PRICE_CACHE_WRITE_PER_MTOK", "3.75",
                "CLAUDE_PRICE_CACHE_READ_PER_MTOK", "0.30")).pricing();
        assertThat(custom).isEqualTo(new DraftsProperties.Pricing(3, 15, 10, 3.75, 0.30));
    }

    @Test
    void effort_defaultsToNull_acceptsTheDocumentedLevels_andRejectsAnythingElse() {
        assertThat(DraftFixtures.properties().effort()).isNull();
        assertThat(DraftFixtures.properties(Map.of("CLAUDE_DRAFT_EFFORT", " Medium ")).effort()).isEqualTo("medium");
        assertThatThrownBy(() -> DraftFixtures.properties(Map.of("CLAUDE_DRAFT_EFFORT", "minimal")))
                .hasStackTraceContaining("CLAUDE_DRAFT_EFFORT");
    }

    @Test
    void promptCache_isOnByDefault_andCanBeTurnedOff() {
        assertThat(DraftFixtures.properties().promptCache()).isTrue();
        assertThat(DraftFixtures.properties(Map.of("CLAUDE_DRAFT_PROMPT_CACHE", "false")).promptCache()).isFalse();
    }
}
