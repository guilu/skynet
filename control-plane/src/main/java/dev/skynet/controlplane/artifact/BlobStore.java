package dev.skynet.controlplane.artifact;

import java.io.IOException;

/**
 * Almacén de contenidos direccionado por su sha256: un mismo contenido se guarda una sola vez. La
 * interfaz permite pasar a S3/MinIO sin cambiar el dominio.
 */
public interface BlobStore {

  /** Guarda el contenido si no estaba y devuelve su uri. */
  String put(String sha256, byte[] content) throws IOException;

  /** Lee hasta {@code limit} bytes desde {@code offset}; vacío si {@code offset} pasa del final. */
  byte[] read(String uri, long offset, int limit) throws IOException;
}
