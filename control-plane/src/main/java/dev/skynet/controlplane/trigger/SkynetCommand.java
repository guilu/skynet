package dev.skynet.controlplane.trigger;

import dev.skynet.controlplane.shared.InvalidRequestException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Una orden {@code /skynet} en un comentario (DS-B). La orden es la primera línea: {@code /skynet
 * <verbo> [resto]}. Para {@code corrige}, el texto es el resto de esa línea y las siguientes.
 *
 * @param verb el verbo, en minúsculas
 * @param rest lo que sigue al verbo en la primera línea
 * @param text el resto de la línea y las siguientes, sin espacios a los lados
 */
record SkynetCommand(String verb, String rest, String text) {

  static final String CORRECT = "corrige";
  static final String CANCEL = "cancela";
  static final String STATUS = "estado";
  static final String RUN = "run";

  /** Lo que no se puede hacer desde GitHub hasta AU. */
  static final Set<String> FORBIDDEN =
      Set.of("aprueba", "aprobar", "approve", "fusiona", "fusionar", "merge");

  private static final Pattern FIRST_LINE =
      Pattern.compile("^/skynet(?:[ \\t]+(\\S+))?[ \\t]*([^\\r\\n]*)(?:\\R([\\s\\S]*))?$");

  private static final Pattern ARG = Pattern.compile("([A-Za-z_][A-Za-z0-9_-]{0,63})=(\\S*)");

  /**
   * La orden de un comentario, o {@code null} si no empieza por {@code /skynet}.
   *
   * @param comment el texto entero del comentario
   */
  static SkynetCommand parse(String comment) {
    if (comment == null) {
      return null;
    }
    String text = comment.strip();
    if (!text.regionMatches(true, 0, "/skynet", 0, 7)
        || (text.length() > 7 && !Character.isWhitespace(text.charAt(7)))) {
      return null;
    }
    Matcher m = FIRST_LINE.matcher("/skynet" + text.substring(7));
    if (!m.matches()) {
      return null;
    }
    String verb = m.group(1) == null ? "" : m.group(1).toLowerCase(Locale.ROOT);
    String rest = m.group(2) == null ? "" : m.group(2).strip();
    String more = m.group(3) == null ? "" : m.group(3).stripTrailing();
    String all = more.isBlank() ? rest : rest.isEmpty() ? more.strip() : rest + "\n" + more;
    return new SkynetCommand(verb, rest, all);
  }

  /** La primera línea, tal cual se guarda. */
  String line() {
    return ("/skynet " + verb + (rest.isEmpty() ? "" : " " + rest)).strip();
  }

  /**
   * Los datos {@code clave=valor} del resto de la línea, como textos.
   *
   * @throws InvalidRequestException si algo no tiene esa forma
   */
  Map<String, String> args() {
    Map<String, String> args = new LinkedHashMap<>();
    if (rest.isEmpty()) {
      return args;
    }
    for (String part : rest.split("\\s+")) {
      Matcher m = ARG.matcher(part);
      if (!m.matches()) {
        throw new InvalidRequestException(
            "`" + part + "` no es un dato: escribe `clave=valor`, sin espacios en el valor");
      }
      args.put(m.group(1), m.group(2));
    }
    return args;
  }
}
