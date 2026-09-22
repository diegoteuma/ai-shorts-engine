package com.aishorts.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Arranca la API REST con clientes REALES (Claude, Higgsfield, ElevenLabs) —
 * a diferencia de DemoRunner, que usa a propósito los Fake* para no gastar
 * ni depender de red real. Correr esto SÍ gasta plata cuando se llaman los
 * endpoints de narración/costos/generación: para eso está.
 *
 * El wiring de beans (credenciales, clientes reales, StoryApprovalService,
 * StoryDraftingService, StoryRepository) vive en
 * {@link com.aishorts.engine.config.EngineConfiguration}. Ver el README,
 * sección "API REST", para las variables de entorno requeridas.
 */
@SpringBootApplication
public class AiShortsEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiShortsEngineApplication.class, args);
    }
}
