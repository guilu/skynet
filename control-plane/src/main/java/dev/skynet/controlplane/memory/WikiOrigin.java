package dev.skynet.controlplane.memory;

/** Quién escribió la versión actual de una página de la wiki. */
public enum WikiOrigin {
  /** Skynet, sin modelo, a partir de lo que pasó en el proyecto (MV-C). */
  GENERATED,
  /** El curador de la memoria (MV-E). */
  CURATOR,
  /** Una persona. */
  HUMAN
}
