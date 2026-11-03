package dev.skynet.controlplane.workflow;

import dev.skynet.protocol.AgentProviderKind;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Cómo se cuenta una espera por la cuota (CQ-A) en los avisos fuera de la web. */
public final class QuotaTexts {

  /** Las horas se dan en esta zona, como el cron de los disparadores. */
  public static final ZoneId ZONE = ZoneId.of("Europe/Madrid");

  private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("'las' HH:mm");
  private static final DateTimeFormatter DAY =
      DateTimeFormatter.ofPattern("'el' EEEE d 'a las' HH:mm", Locale.forLanguageTag("es-ES"));

  private QuotaTexts() {}

  /** El nombre del proveedor: «Claude Code», «Codex»… */
  public static String provider(String id) {
    return AgentProviderKind.byId(id == null ? "" : id)
        .map(AgentProviderKind::displayName)
        .orElse(id);
  }

  /** « (ventana de 5 h)», « (ventana semanal)» o nada. */
  public static String window(String window) {
    if ("five_hour".equals(window)) {
      return " (ventana de 5 h)";
    }
    return "seven_day".equals(window) ? " (ventana semanal)" : "";
  }

  /** «las 15:04» si es hoy; si no, «el jueves 6 a las 10:00». */
  public static String time(Instant at, Instant now) {
    ZonedDateTime when = at.atZone(ZONE);
    boolean today = when.toLocalDate().equals(now.atZone(ZONE).toLocalDate());
    return today ? HOUR.format(when) : DAY.format(when);
  }
}
