package com.aishorts.engine.drafts;

import com.aishorts.engine.claude.ClaudeConfig;
import com.aishorts.engine.claude.ClaudeMessagesClient;
import com.aishorts.engine.duration.WordsPerSecondDurationEstimator;
import com.aishorts.engine.persistence.StoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;

/**
 * Wiring del generador de historias (paso previo al pipeline). Separado de
 * EngineConfiguration para no mezclarlo con los clientes del pipeline: nada
 * de lo que se arma acá depende de Higgsfield ni de ElevenLabs.
 *
 * El único bean que habla con la red es StoryDraftGenerator
 * (ClaudeStoryDraftGenerator); los tests lo reemplazan con un fake @Primary.
 */
@Configuration
@EnableConfigurationProperties(DraftsProperties.class)
public class DraftsConfiguration {

    @Bean
    Clock draftsClock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    SourcePolicy sourcePolicy(DraftsProperties properties) {
        return SourcePolicy.from(properties);
    }

    @Bean
    DraftNormalizer draftNormalizer(DraftsProperties properties, SourcePolicy sourcePolicy, ObjectMapper objectMapper) {
        return new DraftNormalizer(properties, sourcePolicy, objectMapper);
    }

    @Bean
    DraftValidator draftValidator(DraftsProperties properties, SourcePolicy sourcePolicy) {
        return new DraftValidator(properties, sourcePolicy);
    }

    /** Falla el arranque, con un mensaje claro, si el archivo de reglas no está en el classpath. */
    @Bean
    DraftPromptBuilder draftPromptBuilder(DraftsProperties properties, ObjectMapper objectMapper) {
        return new DraftPromptBuilder(DraftPromptBuilder.loadRules(properties.rulesResource()), objectMapper);
    }

    @Bean
    StoryDraftGenerator storyDraftGenerator(ClaudeConfig claudeConfig, ObjectMapper objectMapper, DraftsProperties properties) {
        return new ClaudeStoryDraftGenerator(new ClaudeMessagesClient(claudeConfig, objectMapper), properties);
    }

    @Bean
    JsonFileDraftRepository draftRepository(DraftsProperties properties, ObjectMapper objectMapper) {
        return new JsonFileDraftRepository(Path.of(properties.dir()), objectMapper);
    }

    @Bean
    StoryIndex storyIndex(@Value("${engine.data-dir}") String storiesDir, StoryRepository storyRepository) {
        return new StoryIndex(Path.of(storiesDir), storyRepository);
    }

    @Bean
    StoryDraftService storyDraftService(
            StoryDraftGenerator generator,
            DraftNormalizer normalizer,
            DraftValidator validator,
            DraftPromptBuilder prompts,
            DraftsProperties properties,
            ObjectMapper objectMapper,
            Clock draftsClock
    ) {
        // Mismo estimador que EngineConfiguration le da a StoryDraftingService (POST /stories).
        return new StoryDraftService(generator, normalizer, validator, prompts, properties,
                WordsPerSecondDurationEstimator.neutralSpanish(), objectMapper, draftsClock);
    }
}
