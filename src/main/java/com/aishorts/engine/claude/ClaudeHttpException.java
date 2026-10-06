package com.aishorts.engine.claude;

/**
 * La API de Claude respondió con un status HTTP que no es 2xx. Conserva el
 * status y el cuerpo crudo para que quien llama pueda distinguir casos
 * concretos (por ejemplo, un 400 porque la búsqueda web está deshabilitada
 * en la organización) sin parsear el mensaje de la excepción.
 */
public class ClaudeHttpException extends ClaudeApiException {
    private final int status;
    private final String responseBody;

    public ClaudeHttpException(int status, String responseBody) {
        super("La API de Claude respondió " + status + ": " + responseBody);
        this.status = status;
        this.responseBody = responseBody != null ? responseBody : "";
    }

    public int status() {
        return status;
    }

    public String responseBody() {
        return responseBody;
    }
}
