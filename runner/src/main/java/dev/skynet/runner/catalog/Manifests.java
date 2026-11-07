package dev.skynet.runner.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lo que dicen de sí mismos los ficheros de construcción y el README de un repositorio. Solo lee
 * una lista fija de ficheros, de como mucho {@link #MAX_FILE} bytes: nunca {@code .env} ni nada
 * parecido.
 */
final class Manifests {

  static final int MAX_FILE = 256 * 1024;
  static final int MAX_DESCRIPTION = 300;

  static final List<String> GRADLE_REPORTS = List.of("**/build/test-results/**/*.xml");
  static final List<String> MAVEN_REPORTS = List.of("**/target/surefire-reports/*.xml");

  /** El {@code test} con el que {@code npm init} rellena el package.json: no prueba nada. */
  private static final String NPM_NO_TEST = "no test specified";

  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static final Pattern GRADLE_NAME =
      Pattern.compile("rootProject\\.name\\s*=\\s*[\"']([^\"']+)[\"']");
  private static final Pattern GO_MODULE = Pattern.compile("(?m)^module\\s+(\\S+)");
  private static final Pattern MD_IMAGE = Pattern.compile("!\\[[^\\]]*]\\([^)]*\\)");
  private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");
  private static final Pattern MD_EMPHASIS = Pattern.compile("(\\*\\*|__|\\*|`)");
  private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

  /** Lo deducido; los campos que no se pudieron deducir, a {@code null}. */
  record Facts(
      String tool,
      String name,
      String nameSource,
      String description,
      String descriptionSource,
      String validationCommand,
      List<String> testReportPaths) {}

  private Manifests() {}

  static Facts read(Path repository) {
    String tool = null;
    String name = null;
    String nameSource = null;
    String description = null;
    String descriptionSource = null;
    String validation = null;
    List<String> reports = List.of();

    // Por orden de preferencia: un repositorio con Gradle y un package.json para la web es Gradle.
    Path settingsKts = repository.resolve("settings.gradle.kts");
    Path settings = repository.resolve("settings.gradle");
    if (isFile(settingsKts)
        || isFile(settings)
        || isFile(repository.resolve("build.gradle.kts"))
        || isFile(repository.resolve("build.gradle"))
        || isFile(repository.resolve("gradlew"))) {
      tool = "gradle";
      validation = isFile(repository.resolve("gradlew")) ? "./gradlew check" : "gradle check";
      reports = GRADLE_REPORTS;
      for (Path file : List.of(settingsKts, settings)) {
        String found = match(GRADLE_NAME, text(file));
        if (found != null) {
          name = found;
          nameSource = file.getFileName().toString();
          break;
        }
      }
    }

    Path pom = repository.resolve("pom.xml");
    String pomText = text(pom);
    if (pomText != null) {
      String own = ownPomElements(pomText);
      if (tool == null) {
        tool = "maven";
        validation = isFile(repository.resolve("mvnw")) ? "./mvnw -q verify" : "mvn -q verify";
        reports = MAVEN_REPORTS;
      }
      if (name == null) {
        name = firstNonBlank(element(own, "name"), element(own, "artifactId"));
        nameSource = name == null ? null : "pom.xml";
      }
      String pomDescription = element(own, "description");
      if (description == null && pomDescription != null) {
        description = pomDescription;
        descriptionSource = "pom.xml";
      }
    }

    JsonNode pkg = json(repository.resolve("package.json"));
    if (pkg != null) {
      String manager =
          isFile(repository.resolve("pnpm-lock.yaml"))
              ? "pnpm"
              : isFile(repository.resolve("yarn.lock")) ? "yarn" : "npm";
      if (tool == null) {
        tool = manager;
        String test = pkg.path("scripts").path("test").asString(null);
        validation =
            test == null || test.isBlank() || test.contains(NPM_NO_TEST) ? null : manager + " test";
      }
      String pkgName = pkg.path("name").asString(null);
      if (name == null && pkgName != null && !pkgName.isBlank()) {
        // «@guilu/forma» → «forma».
        name = pkgName.substring(pkgName.lastIndexOf('/') + 1);
        nameSource = "package.json";
      }
      String pkgDescription = pkg.path("description").asString(null);
      if (description == null && pkgDescription != null && !pkgDescription.isBlank()) {
        description = pkgDescription;
        descriptionSource = "package.json";
      }
    }

    String pyproject = text(repository.resolve("pyproject.toml"));
    if (pyproject != null
        || isFile(repository.resolve("setup.py"))
        || isFile(repository.resolve("requirements.txt"))) {
      if (tool == null) {
        tool = "python";
        validation = "pytest";
      }
      String section =
          firstNonBlank(tomlSection(pyproject, "project"), tomlSection(pyproject, "tool.poetry"));
      if (name == null && tomlString(section, "name") != null) {
        name = tomlString(section, "name");
        nameSource = "pyproject.toml";
      }
      if (description == null && tomlString(section, "description") != null) {
        description = tomlString(section, "description");
        descriptionSource = "pyproject.toml";
      }
    }

    String cargo = text(repository.resolve("Cargo.toml"));
    if (cargo != null) {
      if (tool == null) {
        tool = "cargo";
        validation = "cargo test";
      }
      String section = tomlSection(cargo, "package");
      if (name == null && tomlString(section, "name") != null) {
        name = tomlString(section, "name");
        nameSource = "Cargo.toml";
      }
      if (description == null && tomlString(section, "description") != null) {
        description = tomlString(section, "description");
        descriptionSource = "Cargo.toml";
      }
    }

    String goMod = text(repository.resolve("go.mod"));
    if (goMod != null) {
      if (tool == null) {
        tool = "go";
        validation = "go test ./...";
      }
      String module = match(GO_MODULE, goMod);
      if (name == null && module != null) {
        name = module.substring(module.lastIndexOf('/') + 1);
        nameSource = "go.mod";
      }
    }

    if (description == null) {
      Path readme = readme(repository);
      String paragraph = readme == null ? null : firstParagraph(text(readme));
      if (paragraph != null) {
        description = paragraph;
        descriptionSource = readme.getFileName().toString();
      }
    }
    return new Facts(
        tool, name, nameSource, shorten(description), descriptionSource, validation, reports);
  }

  /** El primer párrafo de texto del README: sin título, insignias, HTML ni marcas de Markdown. */
  static String firstParagraph(String readme) {
    if (readme == null) {
      return null;
    }
    StringBuilder paragraph = new StringBuilder();
    boolean inCode = false;
    String[] lines = readme.split("\\R");
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].strip();
      // Título subrayado (setext): la línea siguiente es «====» o «----».
      boolean setextTitle = i + 1 < lines.length && lines[i + 1].strip().matches("[=\\-]{3,}");
      if (line.startsWith("```") || line.startsWith("~~~")) {
        inCode = !inCode;
        continue;
      }
      if (inCode) {
        continue;
      }
      boolean skip =
          setextTitle
              || line.startsWith("#")
              || line.matches("[=\\-]{3,}")
              || line.startsWith("![")
              || line.startsWith("[![")
              || line.startsWith("<")
              || line.startsWith("|")
              || line.startsWith(">")
              || line.startsWith("- ")
              || line.startsWith("* ");
      if (line.isEmpty() || skip) {
        if (!paragraph.isEmpty()) {
          break;
        }
        continue;
      }
      if (!paragraph.isEmpty()) {
        paragraph.append(' ');
      }
      paragraph.append(line);
    }
    if (paragraph.isEmpty()) {
      return null;
    }
    String text = MD_IMAGE.matcher(paragraph).replaceAll("");
    text = MD_LINK.matcher(text).replaceAll("$1");
    text = HTML_TAG.matcher(text).replaceAll("");
    text = MD_EMPHASIS.matcher(text).replaceAll("").replaceAll("\\s+", " ").strip();
    return text.isEmpty() ? null : text;
  }

  /** Como mucho {@link #MAX_DESCRIPTION} caracteres, cortando por una palabra. */
  static String shorten(String text) {
    if (text == null) {
      return null;
    }
    String clean = text.replaceAll("\\s+", " ").strip();
    if (clean.isEmpty()) {
      return null;
    }
    if (clean.length() <= MAX_DESCRIPTION) {
      return clean;
    }
    String cut = clean.substring(0, MAX_DESCRIPTION - 1);
    int space = cut.lastIndexOf(' ');
    return (space > MAX_DESCRIPTION / 2 ? cut.substring(0, space) : cut).strip() + "…";
  }

  private static Path readme(Path repository) {
    try (Stream<Path> entries = Files.list(repository)) {
      return entries
          .filter(p -> isFile(p))
          .filter(
              p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.equals("readme") || n.matches("readme\\.(md|markdown|txt|rst)");
              })
          .sorted()
          .findFirst()
          .orElse(null);
    } catch (IOException e) {
      return null;
    }
  }

  /** El pom sin los bloques cuyos {@code name} o {@code artifactId} no son los del proyecto. */
  private static String ownPomElements(String pom) {
    String own = pom.replaceAll("(?s)<!--.*?-->", "");
    for (String block :
        List.of(
            "parent",
            "dependencies",
            "dependencyManagement",
            "build",
            "profiles",
            "modules",
            "developers",
            "organization",
            "licenses",
            "scm",
            "distributionManagement")) {
      own = own.replaceAll("(?s)<" + block + ">.*?</" + block + ">", "");
    }
    return own;
  }

  private static String element(String xml, String tag) {
    Matcher m = Pattern.compile("(?s)<" + tag + ">(.*?)</" + tag + ">").matcher(xml);
    if (!m.find()) {
      return null;
    }
    String value = m.group(1).replaceAll("\\s+", " ").strip();
    return value.isEmpty() || value.contains("${") ? null : value;
  }

  /** El cuerpo de la sección {@code [name]} de un TOML, hasta la siguiente sección. */
  static String tomlSection(String toml, String name) {
    if (toml == null) {
      return null;
    }
    Matcher m =
        Pattern.compile("(?ms)^\\[" + Pattern.quote(name) + "]\\s*$(.*?)(?=^\\[|\\z)")
            .matcher(toml);
    return m.find() ? m.group(1) : null;
  }

  static String tomlString(String section, String key) {
    if (section == null) {
      return null;
    }
    Matcher m =
        Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*=\\s*([\"'])(.*?)\\1")
            .matcher(section);
    if (!m.find()) {
      return null;
    }
    String value = m.group(2).strip();
    return value.isEmpty() ? null : value;
  }

  private static String match(Pattern pattern, String text) {
    if (text == null) {
      return null;
    }
    Matcher m = pattern.matcher(text);
    return m.find() ? m.group(1).strip() : null;
  }

  private static String firstNonBlank(String a, String b) {
    return a != null && !a.isBlank() ? a : (b != null && !b.isBlank() ? b : null);
  }

  private static JsonNode json(Path file) {
    String text = text(file);
    if (text == null) {
      return null;
    }
    try {
      JsonNode node = JSON.readTree(text);
      return node != null && node.isObject() ? node : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  static boolean isFile(Path file) {
    return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
  }

  /** El fichero como texto, o {@code null} si no existe, no es regular o es demasiado grande. */
  static String text(Path file) {
    if (!isFile(file)) {
      return null;
    }
    try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = in.readNBytes(MAX_FILE + 1);
      if (bytes.length > MAX_FILE) {
        return null;
      }
      return new String(bytes, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return null;
    }
  }
}
