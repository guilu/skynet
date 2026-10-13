package dev.skynet.protocol.runner;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Una comprobación que pide una fase ({@code artifacts} y {@code checks} del YAML) y que el runner
 * ejecuta en el worktree con la orden {@link RunnerCommandType#VERIFY}.
 *
 * <ul>
 *   <li>{@code artifacts}: cada glob de {@code paths} encuentra al menos un fichero.
 *   <li>{@code tests}: el comando de validación del repositorio sale con 0 y no falla ningún test.
 *   <li>{@code diff}: hay commits nuevos o cambios sin confirmar respecto a la base.
 *   <li>{@code commit}: hay commits nuevos y no quedan cambios sin confirmar.
 *   <li>{@code scope}: todos los ficheros cambiados caben en algún glob de {@code paths}.
 *   <li>{@code command}: {@code command} sale con 0.
 * </ul>
 *
 * @param name tipo de comprobación
 * @param command comando de shell de un check {@code command}; {@code null} en los demás
 * @param paths globs relativos al worktree de {@code artifacts} y {@code scope}; vacío en los demás
 */
public record VerificationCheck(String name, String command, List<String> paths) {

  public static final String ARTIFACTS = "artifacts";
  public static final String TESTS = "tests";
  public static final String DIFF = "diff";
  public static final String COMMIT = "commit";
  public static final String SCOPE = "scope";
  public static final String COMMAND = "command";

  /** Los tipos que se escriben por su nombre en {@code checks}. */
  public static final List<String> NAMED = List.of(TESTS, DIFF, COMMIT, SCOPE);

  private static final Set<String> NAMES = Set.of(ARTIFACTS, TESTS, DIFF, COMMIT, SCOPE, COMMAND);

  public VerificationCheck {
    Objects.requireNonNull(name, "name");
    if (!NAMES.contains(name)) {
      throw new IllegalArgumentException("Check desconocido: " + name);
    }
    if (COMMAND.equals(name) == (command == null)) {
      throw new IllegalArgumentException("Solo un check `command` lleva comando");
    }
    paths = paths == null ? List.of() : List.copyOf(paths);
  }

  public static VerificationCheck named(String name) {
    return new VerificationCheck(name, null, List.of());
  }

  public static VerificationCheck command(String command) {
    return new VerificationCheck(COMMAND, command, List.of());
  }

  public static VerificationCheck withPaths(String name, List<String> paths) {
    return new VerificationCheck(name, null, paths);
  }

  /** Lo que comprueba, para enseñarlo junto al resultado: el comando, los globs o nada. */
  public String target() {
    return command != null ? command : paths.isEmpty() ? null : String.join(", ", paths);
  }
}
