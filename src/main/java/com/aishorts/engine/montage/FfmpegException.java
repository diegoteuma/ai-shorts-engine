package com.aishorts.engine.montage;

/**
 * Envuelve tanto fallas de proceso (ffmpeg no está instalado, el proceso
 * fue interrumpido) como salidas de ffmpeg con código de error — en ese
 * segundo caso el mensaje incluye el comando completo y la salida
 * combinada de stdout+stderr que devolvió ffmpeg, porque ahí es donde
 * está la razón real del fallo (filtro mal escrito, códec no soportado,
 * archivo de entrada corrupto, etc.).
 */
public class FfmpegException extends RuntimeException {
    public FfmpegException(String message) {
        super(message);
    }

    public FfmpegException(String message, Throwable cause) {
        super(message, cause);
    }
}
