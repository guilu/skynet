package dev.skynet.controlplane.github;

/**
 * Quien atiende las etiquetas de las issues (los disparadores de DS). Se le llama dentro de la
 * transacción de la entrega, así que no debe llamar a GitHub: solo apuntar lo que haya que hacer.
 */
public interface IssueHandler {

  /**
   * @return si la etiqueta era para él; si lo era, la entrega cuenta como procesada aunque su
   *     repositorio no esté en Skynet
   */
  boolean handle(GitHubIssue issue);
}
