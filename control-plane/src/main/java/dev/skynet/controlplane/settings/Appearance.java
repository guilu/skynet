package dev.skynet.controlplane.settings;

import java.time.Instant;

/**
 * Apariencia de la web: la paleta elegida, el paquete de iconos y cuándo se guardó por última vez
 * cualquiera de los dos ({@code null} si nunca).
 */
public record Appearance(Palette colors, IconPack icons, Instant updatedAt) {}
