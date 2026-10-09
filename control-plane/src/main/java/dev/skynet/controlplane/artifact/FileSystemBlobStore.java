package dev.skynet.controlplane.artifact;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

/** {@link BlobStore} en un directorio: {@code <raíz>/ab/cd/<sha256>}. */
class FileSystemBlobStore implements BlobStore {

  private static final String SCHEME = "fs:";
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

  private final Path root;

  FileSystemBlobStore(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  @Override
  public String put(String sha256, byte[] content) throws IOException {
    Path target = path(sha256);
    if (!Files.exists(target)) {
      Files.createDirectories(target.getParent());
      // Escritura atómica: un lector nunca ve un blob a medias.
      Path temp = Files.createTempFile(target.getParent(), sha256, ".tmp");
      try {
        Files.write(temp, content);
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (FileAlreadyExistsException e) {
        // Otro proceso lo guardó a la vez: el contenido es el mismo.
      } finally {
        Files.deleteIfExists(temp);
      }
    }
    return SCHEME + sha256;
  }

  @Override
  public byte[] read(String uri, long offset, int limit) throws IOException {
    if (!uri.startsWith(SCHEME)) {
      throw new IOException("Uri de blob no soportada: " + uri);
    }
    try (RandomAccessFile file =
        new RandomAccessFile(path(uri.substring(SCHEME.length())).toFile(), "r")) {
      long size = file.length();
      if (offset >= size) {
        return new byte[0];
      }
      byte[] buffer = new byte[(int) Math.min(limit, size - offset)];
      file.seek(offset);
      file.readFully(buffer);
      return buffer;
    }
  }

  @Override
  public void delete(String uri) throws IOException {
    if (!uri.startsWith(SCHEME)) {
      throw new IOException("Uri de blob no soportada: " + uri);
    }
    Files.deleteIfExists(path(uri.substring(SCHEME.length())));
  }

  private Path path(String sha256) throws IOException {
    if (!SHA256.matcher(sha256).matches()) {
      throw new IOException("sha256 no válido: " + sha256);
    }
    return root.resolve(sha256.substring(0, 2)).resolve(sha256.substring(2, 4)).resolve(sha256);
  }
}
