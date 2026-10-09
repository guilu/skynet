package dev.skynet.controlplane.shared;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Escribe {@code archived_at} directamente: las entidades lo leen como propiedad de solo lectura,
 * así que ningún otro guardado lo pisa.
 */
public final class Archiving {

  private Archiving() {}

  /**
   * Marca la fila como archivada en {@code at}, o como no archivada con {@code null}. {@code table}
   * es siempre un nombre fijo del código, nunca algo que llegue de fuera.
   */
  public static void set(JdbcClient jdbc, String table, UUID id, Instant at) {
    jdbc.sql("UPDATE " + table + " SET archived_at = ? WHERE id = ?")
        .params(at == null ? null : Timestamp.from(at), id)
        .update();
  }
}
