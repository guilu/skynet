package dev.skynet.controlplane.github;

import java.time.Instant;

/**
 * Una entrega de webhook recibida, sin su cuerpo ({@code GET /api/github/deliveries}).
 *
 * @param deliveryId {@code X-GitHub-Delivery}
 * @param event {@code X-GitHub-Event}, p. ej. {@code pull_request}
 * @param action la {@code action} del cuerpo, si tiene
 * @param repository {@code owner/nombre} del repositorio, si tiene
 * @param status {@code PENDING}, {@code PROCESSED}, {@code FAILED} o {@code IGNORED}
 * @param attempts veces que se ha intentado procesar
 * @param error por qué falló o se ignoró
 * @param events cuántos eventos {@code github.*} publicó
 */
public record WebhookDelivery(
    String deliveryId,
    String event,
    String action,
    Long installationId,
    String repository,
    String status,
    int attempts,
    String error,
    int events,
    Instant receivedAt,
    Instant processedAt) {}
