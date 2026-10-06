package dev.skynet.controlplane.artifact;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Almacenamiento de artefactos.
 *
 * @param root directorio del {@link FileSystemBlobStore}
 * @param maxSize tamaño máximo de un artefacto; lo que pase se recorta con una marca visible
 * @param maxUpload lo más que se acepta en una subida del runner, antes de redactar y recortar
 */
@ConfigurationProperties("skynet.artifacts")
record ArtifactProperties(Path root, DataSize maxSize, DataSize maxUpload) {

  ArtifactProperties {
    root = root == null ? Path.of("data", "artifacts") : root;
    maxSize = maxSize == null ? DataSize.ofMegabytes(20) : maxSize;
    maxUpload = maxUpload == null ? DataSize.ofMegabytes(64) : maxUpload;
  }
}
