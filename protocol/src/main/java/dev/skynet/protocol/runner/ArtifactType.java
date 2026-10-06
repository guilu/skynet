package dev.skynet.protocol.runner;

/** Tipos de artefacto que sube un runner (docs/implementation-plan.md §M5). */
public enum ArtifactType {
  /** Prompt efectivo de la invocación. */
  PROMPT,
  /** Resultado final del proveedor (el evento {@code result} del NDJSON). */
  RESULT,
  /** NDJSON bruto de la invocación. */
  LOG,
  /** Rama, commits nuevos y archivos modificados respecto al commit base (JSON). */
  GIT_CHANGES,
  /** Diff completo respecto al commit base, con los cambios sin confirmar y los archivos nuevos. */
  DIFF,
  /** Salida (stdout y stderr) del comando de verificación. */
  VERIFICATION_LOG,
  /** Resultado de los informes JUnit de la verificación (JSON). */
  TEST_REPORT
}
