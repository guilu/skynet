package dev.skynet.runner.agent;

import dev.skynet.protocol.runner.StartAgent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/**
 * Los criterios de revisión del repositorio (ES-B): si el worktree tiene {@code .skynet/review.md}
 * (gana) o {@code REVIEW.md}, su texto entra en el prompt del juez o del escéptico en el sitio de
 * {@link StartAgent#REVIEW_MARKER}, debajo de la rúbrica del workflow, que no se sustituye.
 */
final class RepositoryReview {

  /** En este orden: el primero que exista. */
  static final List<String> FILES = StartAgent.REVIEW_FILES;

  /** Lo que se pasa del fichero; si es más largo, se corta y se dice. */
  static final int MAX_BYTES = 8 * 1024;

  /**
   * El prompt con los criterios puestos, y el fichero del que salen ({@code null} si no hay ninguno
   * o el prompt no tiene sitio para ellos).
   */
  record Expanded(String prompt, String file) {}

  private RepositoryReview() {}

  static Expanded expand(String prompt, Path worktree) throws IOException {
    if (prompt == null || !prompt.contains(StartAgent.REVIEW_MARKER)) {
      return new Expanded(prompt, null);
    }
    Path root = worktree.toRealPath();
    for (String name : FILES) {
      Path file = worktree.resolve(name);
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
          || !file.toRealPath().startsWith(root)) {
        continue;
      }
      byte[] bytes;
      try (InputStream in = Files.newInputStream(file)) {
        bytes = in.readNBytes(MAX_BYTES + 1);
      }
      boolean cut = bytes.length > MAX_BYTES;
      String text = decode(bytes, cut ? MAX_BYTES : bytes.length).strip();
      if (text.isEmpty()) {
        continue;
      }
      String section =
          "\n## Criterios de este repositorio\n\n"
              + "El repositorio dice en `"
              + name
              + "` cómo quiere que se le revise. Tenlo en cuenta junto a la rúbrica, que sigue"
              + " valiendo: qué es un fallo aquí, qué no mirar y qué no señalar.\n\n"
              + text
              + "\n"
              + (cut ? "\n(El fichero sigue: pasa de 8 KB y aquí se corta.)\n" : "");
      return new Expanded(prompt.replace(StartAgent.REVIEW_MARKER, section), name);
    }
    return new Expanded(withoutMarker(prompt), null);
  }

  /** El prompt sin el sitio de los criterios, cuando no hay ninguno. */
  static String withoutMarker(String prompt) {
    return prompt == null ? null : prompt.replace(StartAgent.REVIEW_MARKER, "");
  }

  /** Los primeros {@code length} bytes en UTF-8, sin un carácter partido al final. */
  private static String decode(byte[] bytes, int length) {
    for (int end = length; end >= Math.max(0, length - 3); end--) {
      try {
        return StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes, 0, end))
            .toString();
      } catch (CharacterCodingException e) {
        // Un carácter partido al cortar: se prueba con un byte menos.
      }
    }
    return new String(bytes, 0, length, StandardCharsets.UTF_8);
  }
}
