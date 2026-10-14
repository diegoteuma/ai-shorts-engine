package com.aishorts.engine.video;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class FinalVideoPropertiesTest {

    /** Los defaults de application.yml (en segundos). */
    private static final FinalVideoProperties.Timeouts DEFAULTS = new FinalVideoProperties.Timeouts(600, 120, 120, 2400, 1800);

    private static FinalVideoProperties props(boolean checkEnabled) {
        return new FinalVideoProperties(true, "renders", "video-renderer", "node", 1, false, checkEnabled, DEFAULTS);
    }

    @Test
    void totalTimeoutAddsCheckOnlyWhenItRuns() {
        // 600 * 6 + 120 + 120 + 1800 = 5640 s, más 5 min de margen; check suma 2400 s si corre.
        assertThat(props(true).totalTimeout(6)).isEqualTo(Duration.ofSeconds(5640 + 2400 + 300));
        assertThat(props(false).totalTimeout(6)).isEqualTo(Duration.ofSeconds(5640 + 300));
    }
}
