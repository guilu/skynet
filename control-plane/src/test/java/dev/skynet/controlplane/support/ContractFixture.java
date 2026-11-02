package dev.skynet.controlplane.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/**
 * Lo mismo que hace {@code ContractIT} con sus vistas, para las de los paquetes cuyas vistas no son
 * públicas: serializa con el {@code ObjectMapper} de la aplicación y compara con {@code
 * fixtures/contracts/<name>.json}. Se regeneran igual: {@code -Dskynet.contracts.update=true}.
 */
public final class ContractFixture {

  private static final Path DIR = Path.of(System.getProperty("skynet.contracts"));
  private static final boolean UPDATE = Boolean.getBoolean("skynet.contracts.update");

  private ContractFixture() {}

  public static void check(ObjectMapper json, String name, Object view) {
    Path file = DIR.resolve(name + ".json");
    try {
      String actual =
          json.writer(SerializationFeature.INDENT_OUTPUT).writeValueAsString(view) + "\n";
      if (UPDATE || !Files.exists(file)) {
        Files.createDirectories(DIR);
        Files.writeString(file, actual);
      }
      assertThat(json.readTree(actual))
          .as("%s difiere de %s; regenera los contratos si el cambio es intencionado", name, file)
          .isEqualTo(json.readTree(Files.readString(file)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
