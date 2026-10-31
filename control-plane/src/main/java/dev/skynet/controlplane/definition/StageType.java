package dev.skynet.controlplane.definition;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;

/**
 * Tipos de fase de §11.1. El YAML los admite todos para poder escribir ya los workflows de la
 * especificación, pero solo se publica un workflow cuyas fases sabe ejecutar el motor.
 */
public enum StageType {
  AGENT("agent", null),
  COMMAND("command", null),
  PARALLEL("parallel", null),
  CONDITIONAL("conditional", null),
  VERIFICATION("verification", null),
  HUMAN_APPROVAL("human-approval", null),
  GITHUB_PR("github-pr", null),
  GITHUB_CHECK("github-check", "EN-B"),
  GITHUB_MERGE("github-merge", "EN-C"),
  MERGE("merge", null),
  DEPLOY("deploy", null);

  private final String yaml;
  private final String milestone;

  StageType(String yaml, String milestone) {
    this.yaml = yaml;
    this.milestone = milestone;
  }

  /** Nombre en el YAML, p. ej. {@code human-approval}; también el de la API. */
  @JsonValue
  public String yaml() {
    return yaml;
  }

  /** Si el motor ya ejecuta fases de este tipo. */
  public boolean executable() {
    return this == AGENT
        || this == COMMAND
        || this == PARALLEL
        || this == CONDITIONAL
        || this == VERIFICATION
        || this == HUMAN_APPROVAL
        || this == GITHUB_PR;
  }

  /**
   * Si la fase deja un worktree que las siguientes pueden continuar: el suyo en una {@code agent} o
   * {@code command}, el que heredó (sin los cambios de sus hijos) en una {@code parallel} y el que
   * comprobó en una {@code verification}, el que se aprueba en una {@code human-approval}, que lo
   * pasa sin tocarlo, y el que sube una {@code github-pr}.
   */
  public boolean hasWorkspace() {
    return this == AGENT
        || this == COMMAND
        || this == PARALLEL
        || this == VERIFICATION
        || this == HUMAN_APPROVAL
        || this == GITHUB_PR;
  }

  /** Hito en el que se podrá ejecutar, o {@code null} si todavía no está planificado. */
  public String milestone() {
    return milestone;
  }

  static Optional<StageType> ofYaml(String value) {
    return Arrays.stream(values()).filter(t -> t.yaml.equals(value)).findFirst();
  }
}
