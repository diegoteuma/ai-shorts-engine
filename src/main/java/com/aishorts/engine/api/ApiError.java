package com.aishorts.engine.api;

/** Un error de request (400/404/409/...) que ApiServer traduce directo a una respuesta HTTP con ese status. */
final class ApiError extends RuntimeException {
    private final int status;

    ApiError(int status, String message) {
        super(message);
        this.status = status;
    }

    int status() {
        return status;
    }
}
