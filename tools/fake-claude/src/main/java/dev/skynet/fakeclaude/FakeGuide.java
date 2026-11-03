package dev.skynet.fakeclaude;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/**
 * El recorrido guionizado de una guía de pruebas (VA-D), para {@code FAKE_CLAUDE_GUIDE}, {@code
 * FAKE_CODEX_GUIDE} y {@code FAKE_AGY_GUIDE}: la ruta de un fichero testigo. Solo actúa si la
 * salida que se pide es la de una guía (tiene {@code guides} y {@code steps}).
 *
 * <ul>
 *   <li>Busca las guías como dice el prompt: los ficheros que cumplen sus globs y que cambian desde
 *       el commit base ({@code git diff --name-only <base>} y los ficheros nuevos sin seguimiento).
 *   <li>Cada elemento de lista de primer nivel ({@code 1.} o {@code -}) bajo un {@code ##} es un
 *       paso; los de las secciones que el prompt dice que no se ejecutan quedan {@code skipped}.
 *   <li>Sin el testigo, pasan todos. Con él, falla el primer paso que contiene su texto (vacío, el
 *       primero) y se borra el testigo, así que la ronda siguiente pasa.
 * </ul>
 */
final class FakeGuide {

  /** «Son los ficheros que cumplen `a` o `b`». */
  private static final Pattern GLOBS = Pattern.compile("Son los ficheros que cumplen (.+?) y que");

  private static final Pattern QUOTED = Pattern.compile("`([^`]+)`");

  /** «`git diff --name-only <base>`». */
  private static final Pattern BASE = Pattern.compile("`git diff --name-only ([0-9a-fA-F]+)`");

  /**
   * «No ejecutes las secciones cuyo título empieza por «## 4.», «## 5.»». Sin acentos ni comillas
   * en el patrón: según la plataforma, el prompt llega con ellos estropeados.
   */
  private static final Pattern SKIP = Pattern.compile("empieza por (.+?): apunta");

  /** Lo que sobra alrededor de un título: las comillas, enteras o estropeadas. */
  private static final Pattern LEADING = Pattern.compile("^[^#\\p{Alnum}]+");

  private static final Pattern TRAILING = Pattern.compile("[^.\\p{Alnum}]+$");

  private static final Pattern STEP = Pattern.compile("^(?:\\d+\\.|[-*]) +(.+)$");

  private FakeGuide() {}

  /** El recorrido en JSON, o {@code null} si no hay testigo configurado o no es una guía. */
  static String run(
      Map<String, String> env, String variable, String schema, String prompt, Path cwd)
      throws IOException, InterruptedException {
    String marker = env.get(variable);
    if (marker == null
        || marker.isBlank()
        || schema == null
        || !schema.contains("\"guides\"")
        || !schema.contains("\"steps\"")) {
      return null;
    }
    String text = prompt == null ? "" : prompt;
    Path file = cwd.resolve(marker);
    boolean fail = Files.isRegularFile(file);
    String failing = fail ? Files.readString(file, StandardCharsets.UTF_8).strip() : "";
    if (fail) {
      Files.delete(file);
    }
    List<String> skip = new ArrayList<>();
    Matcher s = SKIP.matcher(text);
    if (s.find()) {
      for (String one : s.group(1).split(", ")) {
        String title = TRAILING.matcher(LEADING.matcher(one).replaceAll("")).replaceAll("");
        if (!title.isEmpty()) {
          skip.add(title);
        }
      }
    }
    List<String> guides = guides(text, cwd);
    List<Map<String, Object>> steps = new ArrayList<>();
    boolean failed = false;
    for (String guide : guides) {
      String section = null;
      for (String line : Files.readAllLines(cwd.resolve(guide), StandardCharsets.UTF_8)) {
        if (line.startsWith("## ")) {
          section = line.strip();
          continue;
        }
        Matcher m = STEP.matcher(line);
        if (section == null || !m.matches()) {
          continue;
        }
        String step = m.group(1).strip();
        String current = section;
        boolean skipped = skip.stream().anyMatch(current::startsWith);
        String status = "passed";
        String evidence = "Sale lo que dice la guia (fake).";
        if (skipped) {
          status = "skipped";
          evidence = "Seccion que no se ejecuta (fake).";
        } else if (fail && !failed && (failing.isEmpty() || step.contains(failing))) {
          failed = true;
          status = "failed";
          evidence = "No sale lo que dice la guia (fake).";
        }
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("guide", guide);
        one.put("section", section);
        one.put("step", step);
        one.put("status", status);
        one.put("evidence", evidence);
        steps.add(one);
      }
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("guides", guides);
    out.put("steps", steps);
    out.put(
        "summary",
        guides.isEmpty()
            ? "No hay guia de pruebas en el cambio (fake)."
            : failed ? "Falla un paso de la guia (fake)." : "Pasan todos los pasos (fake).");
    return JsonMapper.shared().writeValueAsString(out);
  }

  /** Las guías que cumplen los globs del prompt y que cambian desde el commit base. */
  private static List<String> guides(String prompt, Path cwd)
      throws IOException, InterruptedException {
    List<PathMatcher> matchers = new ArrayList<>();
    Matcher g = GLOBS.matcher(prompt);
    if (g.find()) {
      Matcher q = QUOTED.matcher(g.group(1));
      while (q.find()) {
        matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + q.group(1)));
      }
    }
    if (matchers.isEmpty()) {
      return List.of();
    }
    Set<String> changed = new LinkedHashSet<>();
    Matcher b = BASE.matcher(prompt);
    if (b.find()) {
      changed.addAll(git(cwd, "diff", "--name-only", b.group(1)));
    }
    changed.addAll(git(cwd, "ls-files", "--others", "--exclude-standard"));
    return changed.stream()
        .filter(p -> matchers.stream().anyMatch(m -> m.matches(Path.of(p))))
        .filter(p -> Files.isRegularFile(cwd.resolve(p)))
        .sorted()
        .toList();
  }

  private static List<String> git(Path cwd, String... args)
      throws IOException, InterruptedException {
    List<String> command = new ArrayList<>(List.of("git"));
    command.addAll(List.of(args));
    Process p =
        new ProcessBuilder(command)
            .directory(cwd.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
      return List.of();
    }
    return out.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
  }
}
