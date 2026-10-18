package dev.skynet.protocol.runner;

import java.time.Instant;
import java.util.Objects;

/**
 * Una muestra de la cuota de uso de un CLI: qué parte de una de sus ventanas (5 h o semanal) está
 * gastada. Los CLIs solo dan porcentajes: ninguno dice cuántos tokens caben.
 *
 * @param provider CLI de la cuenta: {@code claude-code}, {@code codex} o {@code antigravity}
 * @param bucket grupo de modelos con su propia cuota: {@code default} en Claude Code y Codex; en
 *     Antigravity, {@code gemini} o {@code 3p} (Claude y GPT)
 * @param label nombre del grupo para enseñarlo (p. ej. «Gemini Models»), o {@code null} si el CLI
 *     solo tiene uno
 * @param window {@link #FIVE_HOUR} o {@link #SEVEN_DAY}
 * @param usedFraction parte gastada, de 0 a 1
 * @param resetsAt cuándo se renueva la ventana, o {@code null} si el CLI no lo dice
 * @param plan plan de la cuenta (p. ej. {@code plus}), o {@code null}
 * @param observedAt cuándo se leyó
 */
public record QuotaSample(
    String provider,
    String bucket,
    String label,
    String window,
    double usedFraction,
    Instant resetsAt,
    String plan,
    Instant observedAt) {

  public static final String FIVE_HOUR = "five_hour";
  public static final String SEVEN_DAY = "seven_day";
  public static final String DEFAULT_BUCKET = "default";

  public QuotaSample {
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(window, "window");
    Objects.requireNonNull(observedAt, "observedAt");
    bucket = bucket == null || bucket.isBlank() ? DEFAULT_BUCKET : bucket;
    usedFraction = Math.clamp(usedFraction, 0, 1);
  }
}
