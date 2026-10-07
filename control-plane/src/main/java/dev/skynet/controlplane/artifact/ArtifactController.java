package dev.skynet.controlplane.artifact;

import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Artefactos para la web: el listado sin contenido y el contenido por trozos. */
@RestController
class ArtifactController {

  /** Cabecera con el tamaño total del artefacto, para seguir leyendo por trozos. */
  static final String SIZE_HEADER = "X-Artifact-Size";

  static final String OFFSET_HEADER = "X-Artifact-Offset";

  private final ArtifactService service;

  ArtifactController(ArtifactService service) {
    this.service = service;
  }

  @GetMapping("/api/agent-runs/{id}/artifacts")
  List<ArtifactSummary> artifacts(@PathVariable UUID id) {
    return service.ofAgent(id);
  }

  /**
   * Contenido desde {@code offset}, como mucho {@code limit} bytes ({@link
   * ArtifactService#MAX_CHUNK} a lo sumo). El contenido de un artefacto no cambia nunca.
   */
  @GetMapping("/api/artifacts/{id}/content")
  ResponseEntity<byte[]> content(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") long offset,
      @RequestParam(defaultValue = "1048576") int limit) {
    ArtifactContent content = service.content(id, offset, limit);
    return ResponseEntity.ok()
        .contentType(mediaType(content.mediaType()))
        .cacheControl(CacheControl.noCache().cachePrivate())
        .header(SIZE_HEADER, Long.toString(content.size()))
        .header(OFFSET_HEADER, Long.toString(content.offset()))
        .header("X-Content-Type-Options", "nosniff")
        .body(content.bytes());
  }

  /** Lo que es texto se sirve como texto plano UTF-8; nunca como HTML. */
  private static MediaType mediaType(String stored) {
    if (ArtifactService.isText(stored)) {
      MediaType parsed = MediaType.parseMediaType(stored);
      return MediaType.APPLICATION_JSON.isCompatibleWith(parsed)
          ? MediaType.APPLICATION_JSON
          : new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8);
    }
    return MediaType.APPLICATION_OCTET_STREAM;
  }
}
