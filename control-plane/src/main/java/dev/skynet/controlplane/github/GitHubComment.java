package dev.skynet.controlplane.github;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Un comentario nuevo en una issue o una PR ({@code issue_comment.created}), o una revisión que
 * pide cambios ({@code pull_request_review.submitted} con {@code changes_requested}), para las
 * órdenes {@code /skynet} (DS-B). Los textos llegan sin redactar: no son de confianza.
 *
 * @param fullName {@code owner/nombre} del repositorio
 * @param repositoryIds los repositorios de Skynet enlazados con él (puede no haber ninguno)
 * @param number el número de la issue o la PR
 * @param pullRequest si es una PR
 * @param commentId el id del comentario, para reaccionar a él; {@code null} en una revisión
 * @param text el texto del comentario o de la revisión
 * @param sender quien comenta o revisa
 * @param issueAuthor quien escribió la issue o la PR
 */
public record GitHubComment(
    String deliveryId,
    String fullName,
    List<UUID> repositoryIds,
    Kind kind,
    long number,
    boolean pullRequest,
    Long commentId,
    String text,
    String sender,
    String issueAuthor,
    String issueTitle,
    String issueBody,
    String issueUrl,
    Instant receivedAt) {

  public enum Kind {
    /** Un comentario. */
    COMMENT,
    /** Una revisión que pide cambios. */
    CHANGES_REQUESTED
  }
}
