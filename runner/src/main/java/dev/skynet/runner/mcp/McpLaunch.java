package dev.skynet.runner.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Cómo arranca un servidor MCP del runner: {@code --url <control plane> --token-file <fichero>} y
 * las opciones propias de cada uno.
 *
 * @param url el control plane
 * @param token el token, leído del fichero
 * @param options las demás opciones, sin los guiones
 */
public record McpLaunch(String url, String token, Map<String, String> options) {

  /**
   * Lee los argumentos; con uno que falta o que no conoce, explica el uso por stderr y sale con 2.
   *
   * @param command el subcomando, como {@code memory-mcp}
   * @param extra las opciones propias, sin los guiones
   * @param usage el uso de esas opciones, para el mensaje
   */
  public static McpLaunch parse(String[] args, String command, Set<String> extra, String usage)
      throws IOException {
    String url = null;
    Path tokenFile = null;
    Map<String, String> options = new HashMap<>();
    for (int i = 0; i < args.length; i++) {
      String flag = args[i];
      if (!flag.startsWith("--")) {
        usage(command, usage, "argumento desconocido: " + flag);
      }
      if (i + 1 >= args.length) {
        usage(command, usage, "falta el valor de " + flag);
      }
      String value = args[++i];
      switch (flag) {
        case "--url" -> url = value;
        case "--token-file" -> tokenFile = Path.of(value);
        default -> {
          if (!extra.contains(flag.substring(2))) {
            usage(command, usage, "argumento desconocido: " + flag);
          }
          options.put(flag.substring(2), value);
        }
      }
    }
    if (url == null || tokenFile == null) {
      usage(command, usage, "faltan --url y --token-file");
    }
    return new McpLaunch(
        url, Files.readString(tokenFile, StandardCharsets.UTF_8).strip(), Map.copyOf(options));
  }

  /**
   * Atiende por stdio. Nada más que JSON-RPC por stdout: lo que escriba otro código va a stderr.
   */
  public static void serve(StdioMcpServer server) throws IOException, InterruptedException {
    PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    System.setOut(System.err);
    server.serve(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), out);
  }

  private static void usage(String command, String usage, String problem) {
    System.err.println(
        "skynet-runner "
            + command
            + ": "
            + problem
            + "\nUso: skynet-runner "
            + command
            + " --url <control plane> --token-file <fichero>"
            + usage);
    System.exit(2);
  }
}
