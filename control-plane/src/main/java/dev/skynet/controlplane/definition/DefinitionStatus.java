package dev.skynet.controlplane.definition;

/** Estado de una versión de un workflow. */
public enum DefinitionStatus {
  /** Borrador con errores. */
  DRAFT,
  /** Borrador sin errores. */
  VALIDATED,
  /** Publicada: congelada y lista para lanzarse. */
  PUBLISHED;

  static DefinitionStatus ofDraft(Validation validation) {
    return validation.valid() ? VALIDATED : DRAFT;
  }
}
