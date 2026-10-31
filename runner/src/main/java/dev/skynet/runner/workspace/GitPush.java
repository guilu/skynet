package dev.skynet.runner.workspace;

import dev.skynet.protocol.SafeIds;
import dev.skynet.protocol.runner.PushBranch;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * El push de una fase {@code github-pr} (EN-A): confirma lo que el agente dejó sin confirmar,
 * comprueba que nada toca {@code .github/workflows/} y sube {@code HEAD} a la rama remota.
 *
 * <p>El token va a git como cabecera {@code Authorization} con {@code GIT_CONFIG_COUNT}: no está en
 * la URL, en los argumentos (que se ven con {@code ps}), en {@code .git/config} ni en el disco. Si
 * git lo repitiera en un error, se tapa antes de devolverlo.
 */
public final class GitPush {

  /** La carpeta de los workflows de GitHub Actions: la App no tiene permiso para tocarla. */
  static final String WORKFLOWS = ".github/workflows/";

  /** Autor de los cambios sin confirmar cuando el repositorio no tiene uno configurado. */
  static final String AUTHOR_NAME = "Skynet";

  static final String AUTHOR_EMAIL = "skynet@localhost";

  private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

  private GitPush() {}

  /**
   * Lo que se ha subido.
   *
   * @param sha el commit subido
   * @param commits cuántos commits hay desde la base
   * @param committed si se confirmaron cambios que el agente dejó sin confirmar
   * @param output la salida de {@code git push}
   */
  public record Result(String sha, int commits, boolean committed, String output) {}

  /** No se ha podido subir la rama; el mensaje no lleva el token. */
  public static final class PushException extends IOException {
    private static final long serialVersionUID = 1L;

    PushException(String message) {
      super(message);
    }
  }

  /** Comprueba la orden antes de pedir el token: una orden mal formada no lo necesita. */
  public static void check(PushBranch push) throws PushException {
    remote(push.remoteUrl());
    if (!SafeIds.isBranch(push.branch())) {
      throw new PushException("`" + push.branch() + "` no es un nombre de rama válido");
    }
    if (!SafeIds.isCommit(push.base())) {
      throw new PushException("La base `" + push.base() + "` no es un hash de git");
    }
  }

  public static Result push(Path worktree, PushBranch push, String token)
      throws IOException, InterruptedException {
    check(push);
    boolean committed = commitPending(worktree, push.commitMessage());
    String sha = Git.text(worktree, List.of("rev-parse", "HEAD")).trim();
    String base = push.base();
    int commits =
        Integer.parseInt(
            Git.text(worktree, List.of("rev-list", "--count", base + ".." + sha)).trim());
    if (commits == 0) {
      throw new PushException("No hay ningún cambio que subir: el trabajo no tiene commits nuevos");
    }
    String[] touched =
        Git.text(worktree, List.of("diff", "--name-only", "-z", base, sha)).split("\0");
    List<String> blocked = new ArrayList<>();
    for (String file : touched) {
      if (file.startsWith(WORKFLOWS)) {
        blocked.add(file);
      }
    }
    if (!blocked.isEmpty()) {
      throw new PushException(
          "Los cambios tocan "
              + String.join(", ", blocked)
              + ": Skynet no sube cambios en "
              + WORKFLOWS
              + " (la GitHub App no tiene el permiso Workflows)");
    }
    List<String> args = new ArrayList<>(List.of("push", "--porcelain"));
    if (push.force()) {
      args.add("--force");
    }
    args.add("--");
    args.add(push.remoteUrl());
    args.add("HEAD:refs/heads/" + push.branch());
    String output;
    try {
      output =
          new String(
              Git.run(worktree, args, new byte[0], environment(token)), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new PushException(scrub(e.getMessage(), token));
    }
    return new Result(sha, commits, committed, scrub(output, token));
  }

  /**
   * El entorno de git para el push: la cabecera con el token, sin ayudantes de credenciales y sin
   * preguntar nada por la terminal.
   */
  static Map<String, String> environment(String token) {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("GIT_TERMINAL_PROMPT", "0");
    env.put("GIT_ASKPASS", "true");
    env.put("GIT_CONFIG_COUNT", "2");
    env.put("GIT_CONFIG_KEY_0", "credential.helper");
    env.put("GIT_CONFIG_VALUE_0", "");
    env.put("GIT_CONFIG_KEY_1", "http.extraHeader");
    String basic =
        Base64.getEncoder()
            .encodeToString(("x-access-token:" + token).getBytes(StandardCharsets.UTF_8));
    env.put("GIT_CONFIG_VALUE_1", "Authorization: Basic " + basic);
    return env;
  }

  /**
   * Confirma todo lo que haya sin confirmar (salvo lo ignorado), con el autor del repositorio o, si
   * no tiene, {@link #AUTHOR_NAME}. Devuelve si había algo.
   */
  static boolean commitPending(Path worktree, String message)
      throws IOException, InterruptedException {
    if (Git.text(worktree, List.of("status", "--porcelain")).isBlank()) {
      return false;
    }
    Git.text(worktree, List.of("add", "-A"));
    List<String> args = new ArrayList<>();
    if (!hasAuthor(worktree)) {
      args.addAll(List.of("-c", "user.name=" + AUTHOR_NAME, "-c", "user.email=" + AUTHOR_EMAIL));
    }
    args.addAll(List.of("commit", "--no-verify", "-q", "-m", message));
    Git.text(worktree, args);
    return true;
  }

  private static boolean hasAuthor(Path worktree) throws InterruptedException {
    try {
      return !Git.text(worktree, List.of("config", "--get", "user.email")).isBlank();
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * Solo https, o un remoto local para las pruebas: {@code file://} o http a la propia máquina. Sin
   * usuario ni contraseña en la URL.
   */
  static URI remote(String url) throws PushException {
    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException e) {
      throw new PushException("La URL remota `" + url + "` no es válida");
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (uri.getRawUserInfo() != null) {
      throw new PushException("La URL remota no puede llevar credenciales");
    }
    boolean loopback = uri.getHost() != null && LOOPBACK.contains(uri.getHost());
    if (!scheme.equals("https") && !scheme.equals("file") && !(scheme.equals("http") && loopback)) {
      throw new PushException("La URL remota `" + url + "` tiene que ser https");
    }
    return uri;
  }

  static String scrub(String text, String token) {
    if (text == null || token == null || token.isEmpty()) {
      return text;
    }
    String basic =
        Base64.getEncoder()
            .encodeToString(("x-access-token:" + token).getBytes(StandardCharsets.UTF_8));
    return text.replace(token, "[REDACTED]").replace(basic, "[REDACTED]");
  }
}
