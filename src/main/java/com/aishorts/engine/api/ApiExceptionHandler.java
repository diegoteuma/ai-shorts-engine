package com.aishorts.engine.api;

import com.aishorts.engine.montage.MissingMontageInputsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** Traduce las excepciones de la capa de API a respuestas HTTP, igual que el viejo ApiServer.handle(). */
@RestControllerAdvice(basePackageClasses = StoryController.class)
class ApiExceptionHandler {

    @ExceptionHandler(ApiError.class)
    ResponseEntity<Map<String, Object>> handleApiError(ApiError e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "Body no es JSON válido: " + e.getMostSpecificCause().getMessage()));
    }

    /** Faltan clips/audios para el montaje: 400 con la lista exacta de archivos y rutas esperadas. */
    @ExceptionHandler(MissingMontageInputsException.class)
    ResponseEntity<Map<String, Object>> handleMissingMontageInputs(MissingMontageInputsException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        body.put("missing", e.missing());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        String rootCauseMessage = rootCauseMessage(e);
        if (rootCauseMessage != null) {
            body.put("cause", rootCauseMessage);
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    /** Mensaje de la causa raíz (recorriendo getCause() hasta el final), o null si no aporta nada nuevo. */
    private static String rootCauseMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root == e || root.getMessage() == null || root.getMessage().equals(e.getMessage())) {
            return null;
        }
        return root.getMessage();
    }
}
