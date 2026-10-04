package dev.skynet.controlplane.shared;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Oculta secretos en los payloads antes de persistirlos. El event store es inmutable: lo que entra
 * en él no se puede corregir después, así que todo lo que llega de fuera (eventos del runner) pasa
 * por aquí antes de guardarse.
 *
 * <p>Se sustituyen por {@value #MASK}:
 *
 * <ul>
 *   <li>los valores de las variables de entorno marcadas como secretas ({@code
 *       skynet.redaction.secret-env}) que tenga el control plane, si miden al menos {@value
 *       #MIN_SECRET_LENGTH} caracteres;
 *   <li>los formatos conocidos de claves y tokens (Anthropic, OpenAI, GitHub, AWS, Slack, Google,
 *       JWT, claves privadas PEM, cabeceras {@code Authorization});
 *   <li>el valor de asignaciones del tipo {@code API_TOKEN=…} o {@code "password": "…"}, y las
 *       credenciales dentro de URLs ({@code https://usuario:clave@host});
 *   <li>los valores de texto cuya clave en el payload parece un secreto ({@code password}, {@code
 *       token}, {@code api_key}…).
 * </ul>
 *
 * <p>Es una red de seguridad, no una garantía: un secreto sin formato reconocible y que no esté en
 * el entorno del control plane no se detecta.
 */
@Component
public class PayloadRedactor {

  public static final String MASK = "[REDACTED]";
  static final int MIN_SECRET_LENGTH = 8;

  /** Formatos que se sustituyen enteros. */
  private static final List<Pattern> TOKEN_PATTERNS =
      List.of(
          // Bloques PEM de clave privada.
          Pattern.compile(
              "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
          // Anthropic, OpenAI y similares: sk-ant-…, sk-proj-…, sk-….
          Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}"),
          // GitHub: tokens clásicos, de app y finos.
          Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{30,}"),
          Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{20,}"),
          // AWS access key id.
          Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"),
          // Slack.
          Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}"),
          // Google API key.
          Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}"),
          // JWT.
          Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"));

  /** Formatos de los que se conserva el prefijo (grupo 1) y se oculta el resto (grupo 2). */
  private static final List<Pattern> PREFIXED_PATTERNS =
      List.of(
          // Authorization: Bearer xxx / Basic xxx.
          Pattern.compile(
              "(?i)(\\bauthorization\\s*[:=]\\s*[\"']?(?:bearer|basic|token)\\s+)([^\\s\"']+)"),
          Pattern.compile("(?i)(\\bbearer\\s+)([A-Za-z0-9._~+/=-]{16,})"),
          // Credenciales en una URL: esquema://usuario:clave@host.
          Pattern.compile("([a-z][a-z0-9+.-]*://[^\\s:/@]+:)([^\\s/@]+)(?=@)"),
          // NOMBRE_SECRETO=valor, nombre_secreto: valor, "password": "valor".
          Pattern.compile(
              "(?i)((?:\\b|_)(?:[a-z0-9_]*[_-])?(?:password|passwd|pwd|secret|token|api[_-]?key"
                  + "|access[_-]?key|private[_-]?key|client[_-]?secret|credentials?)[\"']?"
                  + "\\s*[:=]\\s*[\"']?)([^\\s\"',;}]{6,})"));

  /** Claves de un payload cuyo valor de texto se oculta entero. */
  private static final Pattern SECRET_KEY =
      Pattern.compile(
          "(?i)^(?:.*[_-])?(?:password|passwd|secret|token|api[_-]?key|authorization"
              + "|access[_-]?key|private[_-]?key|client[_-]?secret|credentials?)$");

  private final List<String> secretValues;

  public PayloadRedactor(RedactionProperties properties, Environment environment) {
    this.secretValues =
        properties.secretEnv().stream()
            .map(environment::getProperty)
            .filter(Objects::nonNull)
            .filter(v -> v.length() >= MIN_SECRET_LENGTH)
            .distinct()
            // Los más largos primero, por si uno contiene a otro.
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();
  }

  /** Copia del payload con los secretos ocultos. No modifica el original. */
  public Map<String, Object> redact(Map<String, ?> payload) {
    Map<String, Object> result = new LinkedHashMap<>();
    payload.forEach(
        (key, value) ->
            result.put(
                key,
                value instanceof String && SECRET_KEY.matcher(key).matches() && !isBlank(value)
                    ? MASK
                    : redactValue(value)));
    return result;
  }

  public String redact(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String result = text;
    for (String secret : secretValues) {
      result = result.replace(secret, MASK);
    }
    for (Pattern pattern : TOKEN_PATTERNS) {
      result = pattern.matcher(result).replaceAll(MASK);
    }
    for (Pattern pattern : PREFIXED_PATTERNS) {
      result = replaceSecondGroup(pattern, result);
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private Object redactValue(Object value) {
    return switch (value) {
      case String s -> redact(s);
      case Map<?, ?> m -> redact((Map<String, ?>) m);
      case List<?> list -> {
        List<Object> copy = new ArrayList<>(list.size());
        list.forEach(item -> copy.add(redactValue(item)));
        yield copy;
      }
      case null, default -> value;
    };
  }

  private static String replaceSecondGroup(Pattern pattern, String text) {
    Matcher matcher = pattern.matcher(text);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      String replacement = MASK.equals(matcher.group(2)) ? matcher.group(2) : MASK;
      matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(1) + replacement));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private static boolean isBlank(Object value) {
    return ((String) value).isBlank();
  }
}
