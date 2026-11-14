package dev.skynet.controlplane.memory;

/**
 * Cuánto se fía Skynet de una observación (MV-A).
 *
 * <ul>
 *   <li>{@link #CONFIRMED}: la escribió una persona o Skynet, o una persona aceptó el trabajo del
 *       que sale. Llega a los agentes en el bloque inicial y en las búsquedas.
 *   <li>{@link #UNCONFIRMED}: la guardó un agente y nadie ha aceptado todavía su trabajo. No va en
 *       el bloque inicial; las búsquedas la devuelven marcada.
 *   <li>{@link #QUARANTINED}: parece una orden para el agente, o una persona la apartó. No llega a
 *       ningún agente hasta que una persona la confirme.
 * </ul>
 */
public enum MemoryTrust {
  CONFIRMED,
  UNCONFIRMED,
  QUARANTINED
}
