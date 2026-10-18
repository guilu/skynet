package dev.skynet.runner.provider;

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
   * Precio de un modelo en US$ por millón de tokens. En Claude, escribir en la caché cuesta 1,25
   * veces la entrada con la duración de 5 minutos y el doble con la de 1 hora.
   */
  public record Price(BigDecimal input, BigDecimal output, BigDecimal cacheRead) {}

  /** Tarifas de la API de Anthropic (septiembre de 2026) y de OpenAI (ver abajo). */
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
    // OpenAI (Codex), según agregadores de precios (octubre de 2026): conviene comprobarlos en la
    // página de precios de OpenAI y corregirlos con SKYNET_MODEL_PRICES si han cambiado.
    DEFAULTS.put("gpt-5.5", price("5", "30", "0.50"));
    DEFAULTS.put("gpt-5.4", price("2.50", "15", "0.25"));
    DEFAULTS.put("gpt-5.3-codex", price("1.75", "14", "0.175"));
    DEFAULTS.put("gpt-5.2-codex", price("1.75", "14", "0.175"));
    DEFAULTS.put("gpt-5.1-codex-mini", price("0.25", "2", "0.025"));
    DEFAULTS.put("gpt-5.1-codex", price("1.25", "10", "0.125"));
    DEFAULTS.put("gpt-5-codex", price("1.25", "10", "0.125"));
    DEFAULTS.put("gpt-5", price("1.25", "10", "0.125"));
    // Google (Antigravity CLI), según agregadores de precios (octubre de 2026), sin el recargo de
    // los
    // prompts de más de 200k tokens: conviene comprobarlos en la página de precios de la API de
    // Gemini y corregirlos con SKYNET_MODEL_PRICES si han cambiado.
    DEFAULTS.put("gemini-3.1-pro", price("2", "12", "0.20"));
    DEFAULTS.put("gemini-3-pro", price("2", "12", "0.20"));
    DEFAULTS.put("gemini-3.1-flash-lite", price("0.25", "1.50", "0.025"));
    DEFAULTS.put("gemini-3-flash", price("0.50", "3", "0.05"));
    DEFAULTS.put("gemini-2.5-pro", price("1.25", "10", "0.125"));
    DEFAULTS.put("gemini-2.5-flash-lite", price("0.10", "0.40", "0.01"));
    DEFAULTS.put("gemini-2.5-flash", price("0.30", "2.50", "0.03"));
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

  public static ModelPrices parse(List<String> lines) {
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
