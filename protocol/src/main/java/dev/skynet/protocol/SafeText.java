package dev.skynet.protocol;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Texto de los eventos que tiene que poder guardarse en {@code jsonb}: PostgreSQL rechaza el
 * carácter NUL, y un agente que lee un ZIP o un JAR lo devuelve en su salida. Los caracteres de
 * control (salvo tabulador, salto de línea y retorno de carro) se sustituyen por su escape visible
 * (una barra invertida, {@code u} y su código en cuatro cifras hexadecimales) y los surrogates
 * sueltos por {@code U+FFFD}. Solo toca texto: los bytes de los artefactos binarios nunca pasan por
 * aquí.
 */
public final class SafeText {

  private static final char REPLACEMENT = '�';

  private SafeText() {}

  /** {@code value} con sus textos saneados, recorriendo mapas (también las claves) y listas. */
  public static Object sanitize(Object value) {
    if (value instanceof String text) {
      return sanitize(text);
    }
    if (value instanceof Map<?, ?> map) {
      return sanitizeMap(map);
    }
    if (value instanceof Collection<?> collection) {
      List<Object> list = new ArrayList<>(collection.size());
      for (Object item : collection) {
        list.add(sanitize(item));
      }
      return list;
    }
    return value;
  }

  /** {@link #sanitize(Object)} de un mapa, conservando el orden. */
  public static Map<String, Object> sanitizeMap(Map<?, ?> map) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      result.put(sanitize(String.valueOf(entry.getKey())), sanitize(entry.getValue()));
    }
    return result;
  }

  /** {@code text} sin NUL, controles incompatibles ni surrogates sueltos; igual si no los tiene. */
  public static String sanitize(String text) {
    int first = firstUnsafe(text);
    if (first < 0) {
      return text;
    }
    StringBuilder out = new StringBuilder(text.length() + 16).append(text, 0, first);
    for (int i = first; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isHighSurrogate(c)
          && i + 1 < text.length()
          && Character.isLowSurrogate(text.charAt(i + 1))) {
        out.append(c).append(text.charAt(++i));
      } else if (Character.isSurrogate(c)) {
        out.append(REPLACEMENT);
      } else if (isUnsafeControl(c)) {
        out.append(String.format("\\u%04x", (int) c));
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  private static int firstUnsafe(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (isUnsafeControl(c)) {
        return i;
      }
      if (Character.isHighSurrogate(c)
          && i + 1 < text.length()
          && Character.isLowSurrogate(text.charAt(i + 1))) {
        i++;
      } else if (Character.isSurrogate(c)) {
        return i;
      }
    }
    return -1;
  }

  private static boolean isUnsafeControl(char c) {
    return c < 0x20 && c != '\t' && c != '\n' && c != '\r';
  }
}
