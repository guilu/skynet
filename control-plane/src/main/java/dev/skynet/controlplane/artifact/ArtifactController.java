package dev.skynet.controlplane.artifact;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Artefactos para la web: el listado sin contenido, el contenido por trozos y la descarga. */
@RestController
class ArtifactController {

  /** Cabecera con el tamaño total del artefacto, para seguir leyendo por trozos. */
  static final String SIZE_HEADER = "X-Artifact-Size";

  static final String OFFSET_HEADER = "X-Artifact-Offset";

  /** Tipos que se descargan tal cual; no se interpretan como página. */
  private static final Set<String> DOWNLOAD_TYPES =
      Set.of("image/png", "image/jpeg", "image/gif", "image/webp", "application/pdf");

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

  /**
   * El artefacto entero como fichero adjunto (#145), con el nombre base de su ruta saneado. Solo
   * por id: nunca se lee una ruta que mande el cliente.
   */
  @GetMapping("/api/artifacts/{id}/download")
  ResponseEntity<byte[]> download(@PathVariable UUID id) {
    ArtifactService.Download download = service.download(id);
    return ResponseEntity.ok()
        .contentType(downloadType(download.mediaType()))
        .contentLength(download.size())
        .cacheControl(CacheControl.noCache().cachePrivate())
        .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(download.name()))
        .header("X-Content-Type-Options", "nosniff")
        .body(service.bytes(download));
  }

  /** Imágenes y PDF con su tipo; el texto como texto plano o JSON; lo demás, binario. */
  static MediaType downloadType(String stored) {
    MediaType parsed;
    try {
      parsed = MediaType.parseMediaType(stored);
    } catch (InvalidMediaTypeException e) {
      return MediaType.APPLICATION_OCTET_STREAM;
    }
    String base = parsed.getType() + "/" + parsed.getSubtype();
    if (DOWNLOAD_TYPES.contains(base.toLowerCase(Locale.ROOT))) {
      return new MediaType(parsed.getType(), parsed.getSubtype());
    }
    return mediaType(stored);
  }

  /**
   * {@code attachment} con el nombre base de {@code name}: {@code filename} en ASCII, sin comillas
   * ni separadores, y {@code filename*} en UTF-8 (RFC 6266).
   */
  static String contentDisposition(String name) {
    String base = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
    StringBuilder clean = new StringBuilder();
    base.codePoints()
        .filter(c -> !Character.isISOControl(c) && "\"\\/;:*?<>|".indexOf(c) < 0)
        .forEach(clean::appendCodePoint);
    String utf8 = clean.toString().strip();
    if (utf8.isEmpty() || utf8.equals(".") || utf8.equals("..")) {
      utf8 = "artefacto";
    }
    String ascii = utf8.replaceAll("[^A-Za-z0-9._-]", "_");
    return "attachment; filename=\""
        + ascii
        + "\"; filename*=UTF-8''"
        + URLEncoder.encode(utf8, StandardCharsets.UTF_8).replace("+", "%20");
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
