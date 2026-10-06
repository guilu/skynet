package dev.skynet.runner.verify;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Lee los informes JUnit XML (Gradle, Maven Surefire y compatibles) que deja el comando de
 * verificación: totales y casos que fallan. Solo cuenta los informes escritos durante la
 * verificación, para no mezclar resultados de ejecuciones anteriores.
 */
public final class JUnitReports {

  /** Casos fallidos que se detallan como mucho. */
  static final int MAX_FAILURES = 200;

  static final int MAX_DETAIL = 4000;

  private static final List<String> SKIPPED_DIRS = List.of(".git", "node_modules");

  private JUnitReports() {}

  /**
   * Resultado de los informes encontrados.
   *
   * @param reports rutas de los informes leídos, relativas al worktree
   * @param unreadable informes que no se pudieron leer (XML roto)
   */
  public record Summary(
      int total,
      int failed,
      int errors,
      int skipped,
      List<String> reports,
      List<String> unreadable,
      List<Map<String, Object>> failures) {

    public boolean found() {
      return !reports.isEmpty();
    }

    public Map<String, Object> totals() {
      Map<String, Object> totals = new LinkedHashMap<>();
      totals.put("total", total);
      totals.put("failed", failed);
      totals.put("errors", errors);
      totals.put("skipped", skipped);
      return totals;
    }

    public Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<>(totals());
      map.put("reports", reports);
      map.put("unreadable", unreadable);
      map.put("failures", failures);
      return map;
    }
  }

  /**
   * Informes de {@code worktree} que casan con {@code globs} y se escribieron desde {@code since}.
   */
  public static Summary read(Path worktree, List<String> globs, Instant since) throws IOException {
    List<Path> files = find(worktree, globs, since);
    Counter counter = new Counter();
    List<String> reports = new ArrayList<>();
    List<String> unreadable = new ArrayList<>();
    for (Path file : files) {
      String relative = worktree.relativize(file).toString();
      try (InputStream in = Files.newInputStream(file)) {
        Element root = parser().parse(in).getDocumentElement();
        counter.root(root);
        reports.add(relative);
      } catch (SAXException | IOException e) {
        unreadable.add(relative);
      }
    }
    return new Summary(
        counter.total,
        counter.failed,
        counter.errors,
        counter.skipped,
        reports,
        unreadable,
        counter.failures);
  }

  static List<Path> find(Path worktree, List<String> globs, Instant since) throws IOException {
    List<PathMatcher> matchers =
        globs.stream().map(g -> FileSystems.getDefault().getPathMatcher("glob:" + g)).toList();
    List<Path> found = new ArrayList<>();
    Files.walkFileTree(
        worktree,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            return SKIPPED_DIRS.contains(dir.getFileName().toString()) && !dir.equals(worktree)
                ? FileVisitResult.SKIP_SUBTREE
                : FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            Path relative = worktree.relativize(file);
            // "**/x" no casa con "x" en la raíz: se prueba también con un directorio delante.
            Path prefixed = Path.of("_").resolve(relative);
            boolean matches =
                matchers.stream().anyMatch(m -> m.matches(relative) || m.matches(prefixed));
            if (attrs.isRegularFile()
                && matches
                && !attrs.lastModifiedTime().toInstant().isBefore(since)) {
              found.add(file);
            }
            return FileVisitResult.CONTINUE;
          }
        });
    found.sort(null);
    return found;
  }

  /** Parser sin DTD ni entidades externas: el XML lo escribe un proceso del agente. */
  private static DocumentBuilder parser() throws IOException {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setExpandEntityReferences(false);
      factory.setXIncludeAware(false);
      return factory.newDocumentBuilder();
    } catch (ParserConfigurationException e) {
      throw new IOException("No se pudo crear el parser XML", e);
    }
  }

  private static final class Counter {
    int total;
    int failed;
    int errors;
    int skipped;
    final List<Map<String, Object>> failures = new ArrayList<>();

    void root(Element root) {
      switch (root.getTagName()) {
        case "testsuites" -> {
          for (Element suite : children(root, "testsuite")) {
            suite(suite);
          }
        }
        case "testsuite" -> suite(root);
        default -> {
          // No es un informe JUnit.
        }
      }
    }

    void suite(Element suite) {
      List<Element> nested = children(suite, "testsuite");
      for (Element child : nested) {
        suite(child);
      }
      for (Element testCase : children(suite, "testcase")) {
        total++;
        Element failure = first(testCase, "failure");
        Element error = first(testCase, "error");
        if (failure != null) {
          failed++;
          detail(suite, testCase, failure, "failure");
        } else if (error != null) {
          errors++;
          detail(suite, testCase, error, "error");
        } else if (first(testCase, "skipped") != null) {
          skipped++;
        }
      }
    }

    void detail(Element suite, Element testCase, Element problem, String kind) {
      if (failures.size() >= MAX_FAILURES) {
        return;
      }
      Map<String, Object> failure = new LinkedHashMap<>();
      failure.put("suite", suite.getAttribute("name"));
      failure.put("className", testCase.getAttribute("classname"));
      failure.put("name", testCase.getAttribute("name"));
      failure.put("kind", kind);
      failure.put("type", problem.getAttribute("type"));
      failure.put("message", problem.getAttribute("message"));
      String text = problem.getTextContent().strip();
      failure.put(
          "details", text.length() > MAX_DETAIL ? text.substring(0, MAX_DETAIL) + "…" : text);
      failures.add(failure);
    }

    private static List<Element> children(Element parent, String tag) {
      List<Element> result = new ArrayList<>();
      NodeList nodes = parent.getChildNodes();
      for (int i = 0; i < nodes.getLength(); i++) {
        Node node = nodes.item(i);
        if (node instanceof Element element && element.getTagName().equals(tag)) {
          result.add(element);
        }
      }
      return result;
    }

    private static Element first(Element parent, String tag) {
      List<Element> found = children(parent, tag);
      return found.isEmpty() ? null : found.getFirst();
    }
  }
}
