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
  PARALLEL("parallel", "W3"),
  CONDITIONAL("conditional", "W3"),
  VERIFICATION("verification", "W4"),
  HUMAN_APPROVAL("human-approval", "W5"),
  GITHUB_PR("github-pr", "S2"),
  GITHUB_CHECK("github-check", "S2"),
  GITHUB_MERGE("github-merge", "S2"),
  MERGE("merge", "S2"),
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
    return this == AGENT || this == COMMAND;
  }

  /** Si la fase trabaja en un worktree, que las siguientes pueden continuar. */
  public boolean hasWorkspace() {
    return this == AGENT || this == COMMAND;
  }

  /** Hito en el que se podrá ejecutar, o {@code null} si todavía no está planificado. */
  public String milestone() {
    return milestone;
  }

  static Optional<StageType> ofYaml(String value) {
    return Arrays.stream(values()).filter(t -> t.yaml.equals(value)).findFirst();
  }
}
