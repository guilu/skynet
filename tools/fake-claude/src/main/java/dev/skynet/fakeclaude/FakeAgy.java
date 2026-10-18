package dev.skynet.fakeclaude;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

/**
 * Sustituto de Antigravity CLI ({@code agy}) para leer su cuota: solo responde a {@code --version}
 * y a {@code agy -p /usage --output-format json}, con la salida grabada con agy 1.4.0 ({@code
 * fixtures/agy/usage.json}) o la de {@code FAKE_AGY_USAGE} (ruta a un fichero).
 */
public final class FakeAgy {

  static final String VERSION = "1.4.0";

  private FakeAgy() {}

  public static void main(String[] args) throws IOException {
    System.exit(run(args, System.getenv(), System.out, System.err));
  }

  static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err)
      throws IOException {
    if (args.length == 1 && "--version".equals(args[0])) {
      out.println(VERSION + " (fake-agy)");
      return 0;
    }
    if (args.length >= 2
        && "-p".equals(args[0])
        && "/usage".equals(args[1])
        && Arrays.asList(args).contains("json")) {
      out.println(usage(env));
      return 0;
    }
    err.println("fake-agy: solo se admite `agy -p /usage --output-format json`");
    return 2;
  }

  private static String usage(Map<String, String> env) throws IOException {
    String file = env.get("FAKE_AGY_USAGE");
    if (file != null && !file.isBlank()) {
      return Files.readString(Path.of(file), StandardCharsets.UTF_8).strip();
    }
    try (InputStream in = FakeAgy.class.getResourceAsStream("/agy/usage.json")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
    }
  }
}
