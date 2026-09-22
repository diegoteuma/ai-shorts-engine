package com.aishorts.engine.config;

import com.aishorts.engine.approval.StoryApprovalService;
import com.aishorts.engine.captions.CaptionTranslationService;
import com.aishorts.engine.captions.ClaudeCaptionTranslationService;
import com.aishorts.engine.claude.ClaudeConfig;
import com.aishorts.engine.difficulty.DefaultSceneDifficultyScorer;
import com.aishorts.engine.domain.GenerationTier;
import com.aishorts.engine.duration.WordsPerSecondDurationEstimator;
import com.aishorts.engine.higgsfield.HiggsfieldClient;
import com.aishorts.engine.higgsfield.HiggsfieldConfig;
import com.aishorts.engine.higgsfield.HiggsfieldRestClient;
import com.aishorts.engine.higgsfield.KnownHiggsfieldPricing;
import com.aishorts.engine.persistence.JsonFileStoryRepository;
import com.aishorts.engine.persistence.StoryRepository;
import com.aishorts.engine.script.ClaudeScriptDraftingService;
import com.aishorts.engine.script.ScriptDraftingService;
import com.aishorts.engine.script.StoryDraftingService;
import com.aishorts.engine.tts.ElevenLabsTtsService;
import com.aishorts.engine.tts.TtsConfig;
import com.aishorts.engine.tts.TtsService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.Map;

/**
 * Wiring de todos los beans "reales" de la API — credenciales desde
 * variables de entorno (Spring las resuelve solo, no hace falta leerlas a
 * mano) y los clientes/servicios concretos que antes armaba ApiServerMain.
 *
 * Todas las variables de entorno que esta clase consume (requeridas y
 * opcionales, con sus defaults) están documentadas en application.yml —
 * ver ese archivo para la lista completa.
 */
@Configuration
public class EngineConfiguration {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer bigDecimalForMoney() {
        // Los montos de costo (EstimateResponse.cost) necesitan precisión
        // exacta de BigDecimal, no double — igual que el parser JSON casero
        // que esto reemplaza.
        return builder -> builder.featuresToEnable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    @Bean
    ClaudeConfig claudeConfig(
            @Value("${claude.api-key}") String apiKey,
            @Value("${claude.model}") String model
    ) {
        return ClaudeConfig.of(apiKey, model);
    }

    @Bean
    HiggsfieldConfig higgsfieldConfig(
            @Value("${higgsfield.base-url}") String baseUrl,
            @Value("${higgsfield.api-key-id}") String apiKeyId,
            @Value("${higgsfield.api-key-secret}") String apiKeySecret,
            @Value("${higgsfield.model-standard}") String standardModel,
            @Value("${higgsfield.model-premium}") String premiumModel
    ) {
        return new HiggsfieldConfig(baseUrl, apiKeyId, apiKeySecret, Map.of(
                GenerationTier.STANDARD, standardModel,
                GenerationTier.PREMIUM, premiumModel
        ), KnownHiggsfieldPricing.defaults());
    }

    @Bean
    TtsConfig ttsConfig(
            @Value("${elevenlabs.api-key}") String apiKey,
            @Value("${elevenlabs.voice-id}") String voiceId
    ) {
        return TtsConfig.of(apiKey, voiceId);
    }

    @Bean
    HiggsfieldClient higgsfieldClient(HiggsfieldConfig config, ObjectMapper objectMapper) {
        return new HiggsfieldRestClient(config, objectMapper);
    }

    @Bean
    TtsService ttsService(TtsConfig config, ObjectMapper objectMapper) {
        return new ElevenLabsTtsService(config, objectMapper);
    }

    @Bean
    ScriptDraftingService scriptDraftingService(ClaudeConfig config, ObjectMapper objectMapper) {
        return new ClaudeScriptDraftingService(config, objectMapper);
    }

    @Bean
    CaptionTranslationService captionTranslationService(ClaudeConfig config, ObjectMapper objectMapper) {
        return new ClaudeCaptionTranslationService(config, objectMapper);
    }

    @Bean
    StoryApprovalService storyApprovalService(HiggsfieldClient higgsfieldClient, HiggsfieldConfig higgsfieldConfig, TtsService ttsService) {
        return new StoryApprovalService(
                higgsfieldClient,
                higgsfieldConfig,
                new DefaultSceneDifficultyScorer(),
                WordsPerSecondDurationEstimator.neutralSpanish(),
                ttsService
        );
    }

    @Bean
    StoryDraftingService storyDraftingService(ScriptDraftingService scriptDraftingService) {
        return new StoryDraftingService(scriptDraftingService, WordsPerSecondDurationEstimator.neutralSpanish());
    }

    @Bean
    StoryRepository storyRepository(@Value("${engine.data-dir}") String dataDir, ObjectMapper objectMapper) {
        return new JsonFileStoryRepository(Path.of(dataDir), objectMapper);
    }

    @Bean
    @Qualifier("narrationAudioDir")
    Path narrationAudioDir(@Value("${engine.audio-dir}") String audioDir) {
        return Path.of(audioDir);
    }
}
