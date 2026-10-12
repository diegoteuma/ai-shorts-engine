package com.aishorts.engine.video;

import com.aishorts.engine.clips.ClipStore;
import com.aishorts.engine.persistence.StoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Beans del video final. Solo existen con engine.final-video.enabled=true
 * (FINAL_VIDEO_ENABLED): la API no tiene autenticación y esto lanza procesos
 * pesados, así que por defecto ni el servicio ni las rutas existen.
 */
@Configuration
@ConditionalOnProperty(prefix = "engine.final-video", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(FinalVideoProperties.class)
public class FinalVideoConfiguration {

    @Bean
    ProcessRunner processRunner() {
        return new SystemProcessRunner();
    }

    /** Un único hilo: los renders van de a uno (la guarda de FinalVideoService decide el 409). */
    @Bean(destroyMethod = "shutdownNow")
    ExecutorService finalVideoExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "final-video");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    FinalVideoService finalVideoService(
            FinalVideoProperties props,
            StoryRepository repository,
            ClipStore clipStore,
            @Qualifier("narrationAudioDir") Path narrationAudioDir,
            ProcessRunner processRunner,
            ObjectMapper objectMapper,
            ExecutorService finalVideoExecutor,
            @Value("${clip-download.expected-width}") int expectedWidth,
            @Value("${clip-download.expected-height}") int expectedHeight
    ) {
        FinalVideoService service = new FinalVideoService(props, repository, clipStore, narrationAudioDir,
                processRunner, objectMapper, finalVideoExecutor, expectedWidth, expectedHeight);
        service.recoverInterruptedJobs();
        return service;
    }
}
