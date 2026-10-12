package com.aishorts.engine.api;

import com.aishorts.engine.video.FinalVideoJob;
import com.aishorts.engine.video.FinalVideoService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Video final de una historia (transcripción + composición + render con
 * HyperFrames, en segundo plano). Solo existe con
 * engine.final-video.enabled=true: la API no tiene autenticación y esto lanza
 * procesos pesados. NO exponer a internet sin autenticación.
 *
 * <pre>
 * POST /stories/{id}/final-video        {"force": true} opcional -> 202 {jobId, storyId, status, stage} + Location
 *                                       404 historia inexistente; 409 render en curso (con jobId) o video ya hecho
 *                                       sin force o escenas sin terminar; 422 datos inválidos (con "scenes")
 * GET  /stories/{id}/final-video        estado del trabajo vigente (404 si nunca hubo uno)
 * GET  /stories/{id}/final-video/file   el MP4 (video/mp4, admite Range); 404 si el trabajo no está COMPLETED
 * </pre>
 */
@RestController
@RequestMapping("/stories")
@ConditionalOnProperty(prefix = "engine.final-video", name = "enabled", havingValue = "true")
public class FinalVideoController {

    private final FinalVideoService service;
    private final ObjectMapper objectMapper;

    public FinalVideoController(FinalVideoService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{id}/final-video")
    public ResponseEntity<Map<String, Object>> start(@PathVariable("id") String id, @RequestBody(required = false) String body) {
        FinalVideoJob job = service.start(id, parseForce(body));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobId", job.id());
        response.put("storyId", job.storyId());
        response.put("status", job.status().name());
        response.put("stage", job.stage());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/stories/" + id + "/final-video"))
                .body(response);
    }

    /**
     * El cuerpo se recibe como texto: así un POST sin cuerpo ni Content-Type
     * (curl -X POST) no termina en 415, y {"force": true} se lee igual.
     */
    private boolean parseForce(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        Object force;
        try {
            Map<?, ?> map = objectMapper.readValue(body, Map.class);
            force = map != null ? map.get("force") : null;
        } catch (JsonProcessingException e) {
            throw new ApiError(400, "Body no es un objeto JSON válido: " + e.getOriginalMessage());
        }
        if (force == null) {
            return false;
        }
        if (!(force instanceof Boolean b)) {
            throw new ApiError(400, "'force' debe ser true o false.");
        }
        return b;
    }

    @GetMapping("/{id}/final-video")
    public FinalVideoJob status(@PathVariable("id") String id) {
        return service.current(id)
                .orElseThrow(() -> new ApiError(404, "La historia '" + id + "' no tiene ningún trabajo de video final."));
    }

    @GetMapping("/{id}/final-video/file")
    public ResponseEntity<Resource> file(@PathVariable("id") String id) {
        return service.finalVideo(id)
                .map(path -> ResponseEntity.ok().contentType(MediaType.valueOf("video/mp4")).<Resource>body(new FileSystemResource(path)))
                .orElseThrow(() -> new ApiError(404, "La historia '" + id + "' no tiene un video final COMPLETED."));
    }
}
