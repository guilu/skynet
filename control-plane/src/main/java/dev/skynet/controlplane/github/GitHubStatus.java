package dev.skynet.controlplane.github;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Cómo está la conexión con GitHub, para Ajustes → GitHub ({@code GET /api/github}).
 *
 * @param configured si están las tres variables de la App
 * @param missing las variables que faltan
 * @param apiUrl la API de GitHub a la que se llama
 * @param appId el id de la App, si está
 * @param app la App según GitHub ({@code GET /app}); {@code null} si no se pudo comprobar
 * @param error por qué no se pudo comprobar, o {@code null}
 * @param checkedAt cuándo se comprobó la App por última vez
 * @param installations las instalaciones de la última sincronización
 * @param repositories los repositorios de Skynet (sin archivar) y su enlace con GitHub
 */
public record GitHubStatus(
    boolean configured,
    List<String> missing,
    String apiUrl,
    String appId,
    App app,
    String error,
    Instant checkedAt,
    List<Installation> installations,
    List<RepositoryLink> repositories) {

  /** La App según GitHub. */
  public record App(String slug, String name, String owner, String htmlUrl) {}

  /**
   * Una cuenta donde está instalada la App.
   *
   * @param repositorySelection {@code all} (todos los repositorios de la cuenta) o {@code selected}
   */
  public record Installation(
      long id,
      String account,
      String accountType,
      String repositorySelection,
      Instant suspendedAt,
      Instant syncedAt,
      List<InstallationRepository> repositories) {}

  /** Un repositorio de GitHub al que llega una instalación. */
  public record InstallationRepository(
      long id, String fullName, @JsonProperty("private") boolean privateRepository) {}

  /**
   * Un repositorio de Skynet y su repositorio en GitHub.
   *
   * @param fullName {@code owner/nombre} según su URL remota, o {@code null} si no es de GitHub
   * @param access {@link Access#CONNECTED} si alguna instalación llega a él
   * @param installationId la instalación que llega a él, si hay
   * @param githubRepositoryId su id en GitHub, si hay instalación
   */
  public record RepositoryLink(
      UUID id,
      UUID projectId,
      String projectKey,
      String name,
      String remoteUrl,
      String fullName,
      Access access,
      Long installationId,
      String installationAccount,
      Long githubRepositoryId) {}

  /** Si Skynet puede trabajar con un repositorio en GitHub. */
  public enum Access {
    /** Una instalación de la App llega a él. */
    CONNECTED,
    /** Está en GitHub, pero ninguna instalación llega a él (o la que llega está suspendida). */
    NOT_INSTALLED,
    /** No tiene URL remota o no es de este GitHub. */
    NOT_GITHUB
  }
}
