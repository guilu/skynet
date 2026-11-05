package dev.skynet.controlplane.github;

/**
 * Quien atiende los webhooks de GitHub Deployments (los despliegues de DL). Se le llama dentro de
 * la transacción de la entrega: si falla, la entrega se reintenta. No debe llamar a GitHub.
 */
public interface DeploymentHandler {

  void handle(GitHubDeployment deployment);
}
