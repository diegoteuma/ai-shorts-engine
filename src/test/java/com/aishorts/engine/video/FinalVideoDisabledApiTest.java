package com.aishorts.engine.video;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Sin FINAL_VIDEO_ENABLED (el default) no existen ni el servicio ni las rutas. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FinalVideoDisabledApiTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void engineProperties(DynamicPropertyRegistry registry) {
        registry.add("DATA_DIR", () -> dataDir.resolve("stories").toString());
        registry.add("AUDIO_DIR", () -> dataDir.resolve("audio").toString());
        registry.add("CLIPS_DIR", () -> dataDir.resolve("clips").toString());
        registry.add("RENDERS_DIR", () -> dataDir.resolve("renders").toString());
        registry.add("CLAUDE_API_KEY", () -> "test-claude-key");
        registry.add("CLAUDE_MODEL", () -> "test-claude-model");
        registry.add("HIGGSFIELD_BASE_URL", () -> "https://higgsfield.invalid");
        registry.add("HIGGSFIELD_API_KEY_ID", () -> "test-id");
        registry.add("HIGGSFIELD_API_KEY_SECRET", () -> "test-secret");
        registry.add("HIGGSFIELD_MODEL_STANDARD",
                () -> com.aishorts.engine.higgsfield.KnownHiggsfieldPricing.STANDARD_MODEL_ID);
        registry.add("HIGGSFIELD_MODEL_PREMIUM",
                () -> com.aishorts.engine.higgsfield.KnownHiggsfieldPricing.PREMIUM_MODEL_ID);
        registry.add("ELEVENLABS_API_KEY", () -> "test-elevenlabs-key");
        registry.add("ELEVENLABS_VOICE_ID", () -> "test-voice");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ApplicationContext context;

    @Test
    void byDefaultThereIsNoServiceNorRoute() {
        assertThat(context.getBeanProvider(FinalVideoService.class).getIfAvailable()).isNull();
        assertThat(context.getBeanProvider(ProcessRunner.class).getIfAvailable()).isNull();

        String base = "http://localhost:" + port + "/stories/cualquiera/final-video";
        assertThat(rest.postForEntity(base, Map.of(), Map.class).getStatusCode().value()).isEqualTo(404);
        assertThat(rest.getForEntity(base, Map.class).getStatusCode().value()).isEqualTo(404);
        assertThat(rest.getForEntity(base + "/file", Map.class).getStatusCode().value()).isEqualTo(404);
        assertThat(dataDir.resolve("renders")).doesNotExist();
    }
}
