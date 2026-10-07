package dev.skynet.runner.agent;

import dev.skynet.protocol.runner.ArtifactType;
import dev.skynet.protocol.runner.ArtifactUpload;
import dev.skynet.runner.journal.Journal;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Deja los artefactos en disco y en el journal para que {@link ArtifactSender} los suba, aunque el
 * runner se reinicie o se corte la conexión. Lo que pase de {@link #MAX_BYTES} se recorta: el
 * control plane recorta además a su propio máximo.
 */
public final class ArtifactSpool {

  /** Lo más que se sube de un artefacto; el control plane acepta hasta 64 MB por subida. */
  static final long MAX_BYTES = 48L * 1024 * 1024;

  private final Journal journal;
  private final Path dir;
  private final Runnable artifactsAvailable;

  public ArtifactSpool(Journal journal, Path dir, Runnable artifactsAvailable) {
    this.journal = journal;
    this.dir = dir;
    this.artifactsAvailable = artifactsAvailable;
  }

  /** Encola un artefacto con contenido en memoria. */
  public void add(
      UUID agentRunId,
      UUID verificationRunId,
      ArtifactType type,
      String name,
      String mediaType,
      byte[] content,
      Map<String, Object> metadata)
      throws IOException {
    add(
        agentRunId,
        verificationRunId,
        type,
        name,
        mediaType,
        new java.io.ByteArrayInputStream(content),
        metadata);
  }

  /** Encola una copia del fichero {@code source} (recortada a {@link #MAX_BYTES}). */
  public void addFile(
      UUID agentRunId,
      UUID verificationRunId,
      ArtifactType type,
      String name,
      String mediaType,
      Path source,
      Map<String, Object> metadata)
      throws IOException {
    try (InputStream in = Files.newInputStream(source)) {
      add(agentRunId, verificationRunId, type, name, mediaType, in, metadata);
    }
  }

  private void add(
      UUID agentRunId,
      UUID verificationRunId,
      ArtifactType type,
      String name,
      String mediaType,
      InputStream content,
      Map<String, Object> metadata)
      throws IOException {
    Files.createDirectories(dir);
    UUID id = UUID.randomUUID();
    Path file = dir.resolve(id.toString());
    MessageDigest digest = sha256();
    try (OutputStream out = new DigestOutputStream(Files.newOutputStream(file), digest)) {
      content.transferTo(new LimitedOutputStream(out, MAX_BYTES));
    }
    journal.queueArtifact(
        id,
        new ArtifactUpload(
            agentRunId,
            verificationRunId,
            type,
            name,
            mediaType,
            HexFormat.of().formatHex(digest.digest()),
            metadata),
        file);
    artifactsAvailable.run();
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 no disponible", e);
    }
  }

  /** Descarta lo que pase del límite sin cortar la copia. */
  private static final class LimitedOutputStream extends OutputStream {
    private final OutputStream out;
    private long remaining;

    LimitedOutputStream(OutputStream out, long limit) {
      this.out = out;
      this.remaining = limit;
    }

    @Override
    public void write(int b) throws IOException {
      if (remaining > 0) {
        out.write(b);
        remaining--;
      }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      int n = (int) Math.min(len, remaining);
      if (n > 0) {
        out.write(b, off, n);
        remaining -= n;
      }
    }
  }
}
