package com.aishorts.engine.clips;

/** Falla al descargar, validar o guardar un clip. El mensaje es apto para mostrarse al operador. */
public class ClipException extends RuntimeException {

    public ClipException(String message) {
        super(message);
    }

    public ClipException(String message, Throwable cause) {
        super(message, cause);
    }
}
