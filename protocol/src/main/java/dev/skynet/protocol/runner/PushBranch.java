package dev.skynet.protocol.runner;

import java.util.Objects;

/**
 * La rama que sube una fase {@code github-pr} (EN-A). El runner confirma lo que quede sin confirmar
 * en el worktree, comprueba que nada toca {@code .github/workflows/} desde {@code base} y sube
 * {@code HEAD} a {@code branch} de {@code remoteUrl}. El token no viaja en la orden: el runner lo
 * pide al control plane justo antes ({@code POST /api/runner/agent-runs/{id}/push-token}) y se lo
 * da a git como cabecera, sin escribirlo en ningún sitio.
 *
 * @param remoteUrl URL https del repositorio en GitHub
 * @param branch rama remota, p. ej. {@code skynet/demo-7}
 * @param base commit del que partió el trabajo: los cambios desde él son los que se suben
 * @param force si sobrescribe la rama remota (un reintento que volvió a una instantánea); solo en
 *     ramas {@code skynet/}
 * @param commitMessage mensaje del commit de lo que quede sin confirmar
 */
public record PushBranch(
    String remoteUrl, String branch, String base, boolean force, String commitMessage) {

  /** Prefijo de las ramas que sube Skynet: solo estas se pueden sobrescribir. */
  public static final String BRANCH_PREFIX = "skynet/";

  public PushBranch {
    Objects.requireNonNull(remoteUrl, "remoteUrl");
    Objects.requireNonNull(branch, "branch");
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(commitMessage, "commitMessage");
    if (force && !branch.startsWith(BRANCH_PREFIX)) {
      throw new IllegalArgumentException("Solo se sobrescriben ramas " + BRANCH_PREFIX);
    }
  }
}
