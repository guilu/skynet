package dev.skynet.controlplane.artifact;

/** Trozo del contenido de un artefacto, con el tamaño total para seguir leyendo. */
public final class ArtifactContent {

  private final String mediaType;
  private final long size;
  private final long offset;
  private final byte[] bytes;

  ArtifactContent(String mediaType, long size, long offset, byte[] bytes) {
    this.mediaType = mediaType;
    this.size = size;
    this.offset = offset;
    this.bytes = bytes;
  }

  public String mediaType() {
    return mediaType;
  }

  /** Tamaño total del artefacto. */
  public long size() {
    return size;
  }

  public long offset() {
    return offset;
  }

  public byte[] bytes() {
    return bytes.clone();
  }
}
