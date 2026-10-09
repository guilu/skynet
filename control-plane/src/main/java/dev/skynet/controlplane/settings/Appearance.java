package dev.skynet.controlplane.settings;

import java.time.Instant;

/** Apariencia de la web: la paleta elegida y cuándo se guardó ({@code null} si nunca). */
public record Appearance(Palette colors, Instant updatedAt) {}
