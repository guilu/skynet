package dev.skynet.runner.provider.claude;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Precios por modelo con los que el runner estima el coste de una invocación mientras corre. Cada
 * entrada se aplica a los modelos cuyo id empieza por su prefijo; gana el prefijo más largo, así
 * que {@code claude-opus-5-5} no cae en {@code claude-opus-5}.
 *
 * <p>Se puede ampliar o corregir con un fichero ({@code SKYNET_MODEL_PRICES}) de líneas {@code
 * prefijo = entrada, salida, lectura de caché}, en US$ por millón de tokens. Las líneas vacías y
 * las que empiezan por {@code #} se ignoran.
 */
public final class ModelPrices {

  /**
   * Precio de un modelo en US$ por millón de tokens. Escribir en la caché cuesta 1,25 veces la
   * entrada con la duración de 5 minutos y el doble con la de 1 hora.
   */
  public record Price(BigDecimal input, BigDecimal output, BigDecimal cacheRead) {}

  /** Tarifas de la API de Anthropic (septiembre de 2026). */
  private static final Map<String, Price> DEFAULTS = new LinkedHashMap<>();

  static {
    DEFAULTS.put("claude-fable-5-1", price("10", "50", "0.25"));
    DEFAULTS.put("claude-mythos-5-1", price("10", "50", "0.25"));
    DEFAULTS.put("claude-fable-5", price("10", "50", "1"));
    DEFAULTS.put("claude-mythos-5", price("10", "50", "1"));
    DEFAULTS.put("claude-opus-5-5", price("4", "20", "0.20"));
    DEFAULTS.put("claude-opus-5", price("5", "25", "0.50"));
    DEFAULTS.put("claude-opus-4", price("5", "25", "0.50"));
    DEFAULTS.put("claude-sonnet-5-5", price("2", "10", "0.20"));
    DEFAULTS.put("claude-sonnet-5", price("2", "10", "0.20"));
    DEFAULTS.put("claude-sonnet-4", price("3", "15", "0.30"));
    DEFAULTS.put("claude-haiku-4", price("1", "5", "0.10"));
  }

  private final Map<String, Price> byPrefix;

  private ModelPrices(Map<String, Price> byPrefix) {
    this.byPrefix = Map.copyOf(byPrefix);
  }

  public static ModelPrices defaults() {
    return new ModelPrices(DEFAULTS);
  }

  /** Los precios por defecto, con las entradas del fichero añadidas o sustituidas. */
  public static ModelPrices load(Path file) throws IOException {
    return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
  }

  static ModelPrices parse(List<String> lines) {
    Map<String, Price> prices = new LinkedHashMap<>(DEFAULTS);
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i).strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      int eq = line.indexOf('=');
      String[] values = eq < 0 ? new String[0] : line.substring(eq + 1).split(",");
      if (eq <= 0 || values.length != 3) {
        throw new IllegalArgumentException(
            "Línea "
                + (i + 1)
                + " de la tabla de precios: se esperaba «prefijo = entrada, salida, lectura de"
                + " caché»: "
                + line);
      }
      try {
        prices.put(
            line.substring(0, eq).strip(),
            price(values[0].strip(), values[1].strip(), values[2].strip()));
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(
            "Línea " + (i + 1) + " de la tabla de precios: número no válido: " + line, e);
      }
    }
    return new ModelPrices(prices);
  }

  /** Precio del modelo, o vacío si no está en la tabla. Ignora sufijos como {@code [1m]}. */
  public Optional<Price> of(String model) {
    if (model == null) {
      return Optional.empty();
    }
    int bracket = model.indexOf('[');
    String id = bracket < 0 ? model : model.substring(0, bracket);
    return byPrefix.entrySet().stream()
        .filter(e -> id.startsWith(e.getKey()))
        .max((a, b) -> Integer.compare(a.getKey().length(), b.getKey().length()))
        .map(Map.Entry::getValue);
  }

  private static Price price(String input, String output, String cacheRead) {
    return new Price(new BigDecimal(input), new BigDecimal(output), new BigDecimal(cacheRead));
  }
}
