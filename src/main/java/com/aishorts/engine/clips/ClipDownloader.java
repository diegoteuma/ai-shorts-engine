package com.aishorts.engine.clips;

import java.nio.file.Path;

/**
 * Puerto para traer los bytes de un clip. Escribe en {@code destination}
 * (un archivo temporal que elige quien llama); no valida que sea un MP4 —
 * eso lo hace {@link ClipStore} con {@link Mp4Probe} antes de publicarlo.
 * Si falla, no deja un archivo parcial en {@code destination}.
 */
public interface ClipDownloader {

    void download(String url, Path destination) throws ClipException;
}
