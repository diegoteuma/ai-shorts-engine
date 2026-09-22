package com.aishorts.engine.script;

public class ScriptDraftingException extends RuntimeException {
    public ScriptDraftingException(String message) {
        super(message);
    }

    public ScriptDraftingException(String message, Throwable cause) {
        super(message, cause);
    }
}
