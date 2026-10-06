package dev.skynet.controlplane.artifact;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Almacenamiento de artefactos.
 *
 * @param root directorio del {@link FileSystemBlobStore}
 * @param maxSize tamaño máximo de un artefacto; lo que pase se recorta con una marca visible
 */
@ConfigurationProperties("skynet.artifacts")
record ArtifactProperties(Path root, DataSize maxSize) {

  ArtifactProperties {
    root = root == null ? Path.of("data", "artifacts") : root;
    maxSize = maxSize == null ? DataSize.ofMegabytes(20) : maxSize;
  }
}
