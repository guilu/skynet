package dev.skynet.controlplane.definition;

import java.util.List;

/** El YAML no se puede guardar o publicar tal como está; lleva los problemas que lo impiden. */
class DefinitionRejectedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient List<Problem> problems;
  private final boolean conflict;

  DefinitionRejectedException(String message, List<Problem> problems, boolean conflict) {
    super(message);
    this.problems = List.copyOf(problems);
    this.conflict = conflict;
  }

  List<Problem> problems() {
    return problems;
  }

  /** 409 si choca con el estado (publicar algo no publicable); si no, 400. */
  boolean conflict() {
    return conflict;
  }
}
