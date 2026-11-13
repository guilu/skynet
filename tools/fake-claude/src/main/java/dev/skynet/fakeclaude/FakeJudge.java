package dev.skynet.fakeclaude;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/**
 * El veredicto guionizado de un juez de Skynet (VA-B), para {@code FAKE_CLAUDE_JUDGE} y {@code
 * FAKE_CODEX_JUDGE}: la ruta de un fichero testigo. Solo actúa si la salida estructurada que se
 * pide es la de un juez (tiene {@code criteria} y {@code findings}).
 *
 * <ul>
 *   <li>Sin el testigo, aprueba: un 5 en cada criterio del prompt y ningún hallazgo.
 *   <li>Con el testigo, suspende con un 2 en cada criterio y un hallazgo que bloquea, y borra el
 *       testigo, así que la ronda siguiente aprueba. El testigo puede decir el hallazgo: {@code
 *       <fichero>[:<línea>] <texto>}; vacío, uno genérico. Con {@code !} delante, las notas son un
 *       5 y solo bloquea el hallazgo (para probar los escépticos). Con {@code =} delante (antes del
 *       {@code !}, si lo lleva), no lo borra: el mismo hallazgo sale en cada ronda.
 * </ul>
 *
 * <p>Se activa sin reiniciar el runner: basta con crear el fichero. Con varios jueces a la vez,
 * suspende el primero que lo lea.
 *
 * <p>Los escépticos (VA-C), con {@code FAKE_CLAUDE_REFUTE} y {@code FAKE_CODEX_REFUTE}, otro
 * testigo: solo si se pide la salida de un escéptico (tiene {@code refuted}). Sin el testigo, no
 * refutan; con él, refutan los hallazgos cuyo prompt contiene el texto del testigo (vacío, todos).
 * No lo borran: todos los escépticos de una ronda contestan lo mismo.
 */
final class FakeJudge {

  /** Los criterios de la rúbrica, tal como los escribe Skynet en el prompt del juez. */
  private static final Pattern CRITERION =
      Pattern.compile("(?m)^- `([a-z][a-z0-9_-]*)` \\(peso (\\d+)");

  /** {@code [clase] } delante del hallazgo (AP-A): su clase; sin ella, {@code tests}. */
  private static final Pattern CATEGORY = Pattern.compile("^\\[([a-z-]+)\\]\\s*(.*)$");

  /** {@code src/X.java:12 texto} o {@code src/X.java texto}. */
  private static final Pattern FINDING = Pattern.compile("^(\\S+?)(?::(\\d+))?\\s+(.+)$");

  private FakeJudge() {}

  /** El veredicto en JSON, o {@code null} si no hay testigo configurado o no es un juez. */
  static String verdict(
      Map<String, String> env, String variable, String schema, String prompt, Path cwd)
      throws IOException {
    String marker = env.get(variable);
    if (marker == null
        || marker.isBlank()
        || schema == null
        || !schema.contains("\"criteria\"")
        || !schema.contains("\"findings\"")) {
      return null;
    }
    Path file = cwd.resolve(marker);
    boolean fail = Files.isRegularFile(file);
    String scripted = fail ? Files.readString(file, StandardCharsets.UTF_8).strip() : "";
    // Con «=» delante, el testigo no se borra: el mismo hallazgo sale en cada ronda (RH-B).
    boolean keep = scripted.startsWith("=");
    if (keep) {
      scripted = scripted.substring(1).strip();
    }
    if (fail && !keep) {
      Files.delete(file);
    }
    // Con «!» delante, las notas son buenas y solo bloquea el hallazgo (para los escépticos).
    boolean onlyFinding = scripted.startsWith("!");
    if (onlyFinding) {
      scripted = scripted.substring(1).strip();
    }
    boolean fromFile = schema.contains("\"weight\"");
    List<Map<String, Object>> criteria = new ArrayList<>();
    Matcher m = CRITERION.matcher(prompt == null ? "" : prompt);
    while (m.find()) {
      criteria.add(criterion(m.group(1), fail && !onlyFinding, fromFile));
    }
    if (criteria.isEmpty()) {
      criteria.add(criterion("resuelve", fail && !onlyFinding, fromFile));
    }
    List<Map<String, Object>> findings = new ArrayList<>();
    if (fail) {
      String category = "tests";
      Matcher c = CATEGORY.matcher(scripted);
      if (c.matches()) {
        category = c.group(1);
        scripted = c.group(2).strip();
      }
      Map<String, Object> finding = new LinkedHashMap<>();
      Matcher f = FINDING.matcher(scripted);
      if (!scripted.isEmpty() && f.matches() && (f.group(1).contains("/") || f.group(2) != null)) {
        finding.put("file", f.group(1));
        finding.put("line", f.group(2) == null ? null : Integer.parseInt(f.group(2)));
        finding.put("severity", "blocking");
        finding.put("text", f.group(3));
      } else {
        finding.put("file", "");
        finding.put("line", null);
        finding.put("severity", "blocking");
        finding.put(
            "text", scripted.isEmpty() ? "Falta un test para el caso que pide la tarea" : scripted);
      }
      if (schema.contains("\"category\"")) {
        finding.put("category", category);
      }
      findings.add(finding);
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("criteria", criteria);
    out.put("findings", findings);
    out.put(
        "summary",
        fail
            ? "No está terminado: hay un problema que bloquea (fake)."
            : "Cumple la rúbrica (fake).");
    return JsonMapper.shared().writeValueAsString(out);
  }

  /**
   * La respuesta de un escéptico en JSON, o {@code null} si no hay testigo configurado o no es un
   * escéptico.
   */
  static String refutation(
      Map<String, String> env, String variable, String schema, String prompt, Path cwd)
      throws IOException {
    String marker = env.get(variable);
    if (marker == null
        || marker.isBlank()
        || schema == null
        || !schema.contains("\"refuted\"")
        || schema.contains("\"criteria\"")) {
      return null;
    }
    Path file = cwd.resolve(marker);
    boolean refute = false;
    if (Files.isRegularFile(file)) {
      String match = Files.readString(file, StandardCharsets.UTF_8).strip();
      refute = match.isEmpty() || (prompt != null && prompt.contains(match));
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("refuted", refute);
    out.put(
        "reason",
        refute
            ? "Ya se comprueba antes (fake)."
            : "El problema es real: no lo he podido refutar (fake).");
    return JsonMapper.shared().writeValueAsString(out);
  }

  private static Map<String, Object> criterion(String id, boolean fail, boolean fromFile) {
    Map<String, Object> c = new LinkedHashMap<>();
    c.put("id", id);
    c.put("score", fail ? 2 : 5);
    c.put("reason", fail ? "Le falta lo que dice el hallazgo (fake)" : "Bien (fake)");
    if (fromFile) {
      c.put("weight", 1);
      c.put("required", false);
    }
    return c;
  }
}
