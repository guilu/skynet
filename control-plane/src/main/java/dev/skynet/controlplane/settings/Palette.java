package dev.skynet.controlplane.settings;

import jakarta.validation.constraints.Pattern;

/**
 * Colores base de la web en el tema claro, como {@code #rrggbb}. Un color ausente usa el de Skynet.
 * La web deriva de cada uno sus variantes (sombra, fondo suave, tinta) y las del tema oscuro.
 */
public record Palette(
    @Pattern(regexp = HEX, message = HEX_MESSAGE) String primary,
    @Pattern(regexp = HEX, message = HEX_MESSAGE) String ok,
    @Pattern(regexp = HEX, message = HEX_MESSAGE) String warn,
    @Pattern(regexp = HEX, message = HEX_MESSAGE) String bad,
    @Pattern(regexp = HEX, message = HEX_MESSAGE) String live,
    @Pattern(regexp = HEX, message = HEX_MESSAGE) String idle) {

  static final String HEX = "^#[0-9a-fA-F]{6}$";
  static final String HEX_MESSAGE = "debe ser un color #rrggbb";

  static final Palette DEFAULT = new Palette(null, null, null, null, null, null);

  /** La misma paleta con los colores en minúsculas, para guardarla siempre igual. */
  Palette normalized() {
    return new Palette(
        lower(primary), lower(ok), lower(warn), lower(bad), lower(live), lower(idle));
  }

  private static String lower(String color) {
    return color == null ? null : color.toLowerCase(java.util.Locale.ROOT);
  }
}
