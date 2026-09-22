package com.aishorts.engine.persistence;

import com.aishorts.engine.domain.Story;

import java.util.List;
import java.util.Optional;

/**
 * Guarda y recupera historias completas (Story + todas sus Scene, con su
 * estado de aprobación/costo/generación) entre ejecuciones.
 *
 * A propósito, ni {@link com.aishorts.engine.approval.StoryApprovalService}
 * ni {@link com.aishorts.engine.script.StoryDraftingService} saben que esto
 * existe: quien orquesta el flujo (la demo, la API REST) es responsable de
 * leer la Story antes de operar sobre ella y guardarla después de cada
 * paso. Mantener la persistencia afuera de esas clases es lo que permite
 * seguir probándolas con historias solo-en-memoria, como hace DemoRunner.
 */
public interface StoryRepository {

    void save(Story story);

    Optional<Story> findById(String storyId);

    List<Story> findAll();
}
