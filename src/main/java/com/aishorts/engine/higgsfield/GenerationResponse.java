package com.aishorts.engine.higgsfield;

/**
 * Respuesta al encolar una generación.
 *
 * statusUrl es la URL exacta que hay que consultar para el polling (la
 * documentación de Higgsfield describe el flujo como "guardar el request_id
 * devuelto" y luego "hacer polling de status_url"). No se reconstruye una
 * URL de status a partir de una convención propia: se usa tal cual la que
 * devuelve la API.
 */
public record GenerationResponse(String requestId, String statusUrl, String status) {
}
