package dev.skynet.runner.provider;

import dev.skynet.protocol.AgentEventType;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Los errores con los que un CLI dice que la cuenta se quedó sin cuota (CQ-A), para los proveedores
 * que no lo cuentan con un evento propio, como Codex: «You've hit your usage limit…». Solo el
 * límite de la suscripción: los de crédito o de gasto no se arreglan esperando, y un «rate limit»
 * pasajero (demasiadas peticiones por minuto) no es la cuota.
 */
public final class UsageLimits {

  private static final Pattern USAGE_LIMIT =
      Pattern.compile(
          "usage limit|usage_limit|usagelimitexceeded|hit your limit|quota exceeded"
              + "|quota exhausted");

  private static final Pattern NOT_A_WINDOW =
      Pattern.compile("credit|billing|spend|insufficient_quota|payment");

  private UsageLimits() {}

  /** Si el mensaje de error dice que la cuenta se quedó sin cuota de su suscripción. */
  public static boolean matches(String message) {
    if (message == null) {
      return false;
    }
    String lower = message.toLowerCase(Locale.ROOT);
    return USAGE_LIMIT.matcher(lower).find() && !NOT_A_WINDOW.matcher(lower).find();
  }

  /**
   * El evento {@code agent.rate_limit} bloqueante de {@code provider}, sin ventana ni hora: el
   * control plane las saca de la última muestra de la cuota (CU).
   */
  public static ParsedEvent blocked(String provider, String message) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("status", "rejected");
    payload.put("blocked", true);
    payload.put("provider", provider);
    payload.put("message", EventJson.truncate(message, 500));
    return new ParsedEvent(AgentEventType.RATE_LIMIT, payload);
  }
}
