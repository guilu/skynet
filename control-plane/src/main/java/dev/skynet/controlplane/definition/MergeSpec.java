package dev.skynet.controlplane.definition;

import java.util.List;

/**
 * Cómo fusiona la PR una fase {@code github-merge} (EN-C): {@code method} y {@code deleteBranch}
 * del YAML.
 *
 * @param method {@code squash} (lo predeterminado), {@code merge} o {@code rebase}
 * @param deleteBranch si borra la rama de la PR al fusionarla (por defecto, sí)
 */
public record MergeSpec(String method, boolean deleteBranch) {

  public static final List<String> METHODS = List.of("squash", "merge", "rebase");

  public static final MergeSpec DEFAULT = new MergeSpec("squash", true);

  public MergeSpec {
    method = method == null ? "squash" : method;
  }
}
