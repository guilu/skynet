package dev.skynet.controlplane.settings;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Paquete de iconos de la web (IC-A). La web sabe pintar cada uno; aquí solo se guarda cuál. */
public enum IconPack {
  LUCIDE,
  ILUSTRADOS;

  static final IconPack DEFAULT = LUCIDE;

  @JsonValue
  String id() {
    return name().toLowerCase(Locale.ROOT);
  }

  @JsonCreator
  static IconPack of(String id) {
    for (IconPack pack : values()) {
      if (pack.id().equals(id)) return pack;
    }
    throw new IllegalArgumentException("paquete de iconos desconocido: " + id);
  }
}
