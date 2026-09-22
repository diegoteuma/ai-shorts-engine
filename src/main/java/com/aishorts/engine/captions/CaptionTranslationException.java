package com.aishorts.engine.captions;

public class CaptionTranslationException extends RuntimeException {
    public CaptionTranslationException(String message) {
        super(message);
    }

    public CaptionTranslationException(String message, Throwable cause) {
        super(message, cause);
    }
}
