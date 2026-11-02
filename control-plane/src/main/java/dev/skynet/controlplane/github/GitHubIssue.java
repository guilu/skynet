package dev.skynet.controlplane.github;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Una etiqueta puesta en una issue de GitHub ({@code issues.labeled}, DS-A), tal como la da el
 * webhook. Los textos llegan sin redactar: son de GitHub y no son de confianza, así que quien los
 * use los redacta y los recorta.
 *
 * @param deliveryId el {@code X-GitHub-Delivery} de la entrega
 * @param fullName {@code owner/nombre} del repositorio de la issue
 * @param repositoryIds los repositorios de Skynet enlazados con él (puede no haber ninguno: un
 *     repositorio de órdenes no tiene por qué estar en Skynet)
 * @param label la etiqueta que se acaba de poner
 * @param author quien escribió la issue
 * @param sender quien puso la etiqueta
 */
public record GitHubIssue(
    String deliveryId,
    String fullName,
    List<UUID> repositoryIds,
    String label,
    long number,
    String title,
    String body,
    String url,
    String author,
    String sender,
    Instant receivedAt) {}
