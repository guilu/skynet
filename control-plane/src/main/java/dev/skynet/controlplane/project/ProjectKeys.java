package dev.skynet.controlplane.project;

import java.text.Normalizer;
import java.util.Locale;

/** La clave que se propone para un proyecto a partir de su nombre (IP-B). */
final class ProjectKeys {

  /** Lo que admite una clave: {@code ^[A-Z][A-Z0-9]{1,9}$}. */
  static final int MAX = 10;

  /** Un nombre de hasta estas letras se usa entero; uno más largo, sus 3 primeras. */
  static final int WHOLE = 6;

  private ProjectKeys() {}

  /** «forma» → FORMA, «akademia» → AKA, «Árbol 2» → ARBOL2, «42» → P42. */
  static String base(String name) {
    String letters =
        Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toUpperCase(Locale.ROOT)
            .replaceAll("[^A-Z0-9]", "");
    if (letters.isEmpty()) {
      return "PRJ";
    }
    if (!Character.isLetter(letters.charAt(0))) {
      letters = "P" + letters;
    }
    String key = letters.length() <= WHOLE ? letters : letters.substring(0, 3);
    return key.length() < 2 ? key + "X" : key;
  }
}
