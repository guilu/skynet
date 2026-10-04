package dev.skynet.controlplane.workitem;

/** Tipos de trabajo de §5.2 de la especificación. */
public enum WorkItemType {
  FEATURE,
  BUG,
  REFACTOR,
  DEPENDENCY_UPDATE,
  INCIDENT,
  SECURITY_REVIEW
}
