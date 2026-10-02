package com.aishorts.engine.montage;

import java.util.List;

/**
 * El montaje no puede arrancar porque faltan clips o audios en disco. Se
 * lanza ANTES de correr ffmpeg (y antes de armar los .srt), con la lista
 * completa de lo que falta, no solo el primero — la API lo traduce a 400.
 */
public class MissingMontageInputsException extends RuntimeException {

    private final List<MissingMontageInput> missing;

    public MissingMontageInputsException(String storyId, List<MissingMontageInput> missing) {
        super("No se puede montar '" + storyId + "', faltan archivos:\n- "
                + String.join("\n- ", missing.stream().map(MissingMontageInput::description).toList()));
        this.missing = List.copyOf(missing);
    }

    public List<MissingMontageInput> missing() {
        return missing;
    }
}
