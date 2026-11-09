package dev.skynet.protocol.runner;

import java.util.List;

/**
 * Las reglas por defecto del nivel de riesgo de un cambio (AU-A), que el runner aplica con el check
 * {@link VerificationCheck#RISK}. El riesgo lo decide el diff, nunca el agente:
 *
 * <ul>
 *   <li><b>Nivel 0:</b> todo lo que cambia es documentación o texto ({@link #DOCS}).
 *   <li><b>Nivel 1:</b> código normal, incluidos los tests ({@link #TESTS}).
 *   <li><b>Nivel 2:</b> una ruta de {@link #LEVEL_2_PATHS} o una línea añadida en código que casa
 *       con {@link #LEVEL_2_PATTERNS}: migraciones, CI, dependencias, despliegue, seguridad y
 *       secretos. Pide siempre a una persona.
 * </ul>
 *
 * <p>Las rutas y los patrones del repositorio y del workflow se suman a estos y solo pueden subir
 * el nivel. Los tests y la documentación no cuentan para las líneas cambiadas.
 */
public final class RiskRules {

  /** Un patrón de contenido con lo que significa, para el motivo. */
  public record Pattern(String regex, String label) {}

  /** Documentación y texto: nivel 0 y fuera de las líneas. */
  public static final List<String> DOCS =
      List.of(
          "**.md",
          "**.markdown",
          "**.txt",
          "**.rst",
          "**.adoc",
          "docs/**",
          "plans/**",
          "**.png",
          "**.jpg",
          "**.jpeg",
          "**.gif",
          "**.webp",
          "**.svg",
          "LICENSE",
          "NOTICE");

  /** Tests: nivel 1, pero fuera de las líneas cambiadas. */
  public static final List<String> TESTS =
      List.of(
          "**/src/test/**",
          "**/test/**",
          "**/tests/**",
          "**/__tests__/**",
          "**/e2e/**",
          "**Test.java",
          "**IT.java",
          "**Test.kt",
          "**.test.*",
          "**.spec.*",
          "**/test_*.py",
          "**_test.py",
          "**_test.go",
          "fixtures/**",
          "**/fixtures/**");

  /** Rutas de nivel 2. */
  public static final List<String> LEVEL_2_PATHS =
      List.of(
          "**/db/migration/**",
          ".github/workflows/**",
          ".github/actions/**",
          "**build.gradle",
          "**build.gradle.kts",
          "**settings.gradle",
          "**settings.gradle.kts",
          "**gradle.properties",
          "gradle/**",
          "build-logic/**",
          "**pom.xml",
          "**package.json",
          "**package-lock.json",
          "**pnpm-lock.yaml",
          "**yarn.lock",
          "**requirements*.txt",
          "**pyproject.toml",
          "**go.mod",
          "**Cargo.toml",
          "workflows/*.yaml",
          "workflows/*.yml",
          "deploy/**",
          "**Dockerfile",
          "**docker-compose*.yml",
          "**compose*.yaml",
          "**/security/**",
          "**/auth/**",
          "**.env",
          "**.env.*",
          "**/.env*");

  /**
   * Lo que sube a nivel 2 una línea añadida en código (no en tests ni en documentación), sin
   * distinguir mayúsculas. «token» a secas no está: sale en demasiado código que no es un secreto.
   */
  public static final List<Pattern> LEVEL_2_PATTERNS =
      List.of(
          new Pattern(
              "\\b(create|alter|drop)\\s+(table|index|schema|type|view|sequence|extension)\\b",
              "esquema de BD"),
          new Pattern("\\btruncate\\s+table\\b", "esquema de BD"),
          new Pattern(
              "\\b(password|passwd|secret|api[_-]?key|private[_-]?key|access[_-]?token"
                  + "|refresh[_-]?token|client[_-]?secret)\\b",
              "secretos"),
          new Pattern(
              "(bcrypt|passwordencoder|@preauthorize|@secured|@rolesallowed|securityfilterchain"
                  + "|authorizehttprequests)",
              "autenticación"),
          new Pattern("(set-cookie|httponly|samesite)", "cookies de sesión"),
          new Pattern("^\\s*permissions\\s*:", "permisos"));

  /** Líneas cambiadas (sin tests ni documentación) como mucho para que un cambio sea pequeño. */
  public static final int SMALL_LINES = 400;

  private RiskRules() {}
}
