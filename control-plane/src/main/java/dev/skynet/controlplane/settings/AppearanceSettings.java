package dev.skynet.controlplane.settings;

import dev.skynet.controlplane.shared.TimeSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * La apariencia guardada en {@code app_setting}: la paleta bajo la clave {@code appearance} y el
 * paquete de iconos bajo {@code icons}, cada una con su fecha.
 */
@Service
class AppearanceSettings {

  private static final String COLORS = "appearance";
  private static final String ICONS = "icons";

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final TimeSource time;

  AppearanceSettings(JdbcClient jdbc, ObjectMapper json, TimeSource time) {
    this.jdbc = jdbc;
    this.json = json;
    this.time = time;
  }

  private record Stored<T>(T value, Instant updatedAt) {}

  @Transactional(readOnly = true)
  Appearance get() {
    Optional<Stored<Palette>> colors = read(COLORS, Palette.class);
    Optional<Stored<IconPack>> icons = read(ICONS, IconPack.class);
    Instant updatedAt =
        latest(
            colors.map(Stored::updatedAt).orElse(null), icons.map(Stored::updatedAt).orElse(null));
    return new Appearance(
        colors.map(Stored::value).orElse(Palette.DEFAULT),
        icons.map(Stored::value).orElse(IconPack.DEFAULT),
        updatedAt);
  }

  /** Guarda lo que llegue; lo que llegue vacío ({@code null}) se queda como estaba. */
  @Transactional
  Appearance save(Palette colors, IconPack icons) {
    Instant now = time.now();
    if (colors != null) write(COLORS, colors.normalized(), now);
    if (icons != null) write(ICONS, icons, now);
    return get();
  }

  private <T> Optional<Stored<T>> read(String key, Class<T> type) {
    return jdbc.sql("SELECT value, updated_at FROM app_setting WHERE key = ?")
        .param(key)
        .query(
            (rs, row) ->
                new Stored<>(
                    json.readValue(rs.getString("value"), type),
                    rs.getTimestamp("updated_at").toInstant()))
        .optional();
  }

  private void write(String key, Object value, Instant now) {
    jdbc.sql(
            "INSERT INTO app_setting (key, value, updated_at) VALUES (?, ?::jsonb, ?)"
                + " ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value,"
                + " updated_at = EXCLUDED.updated_at")
        .params(key, json.writeValueAsString(value), Timestamp.from(now))
        .update();
  }

  private static Instant latest(Instant a, Instant b) {
    if (a == null) return b;
    if (b == null) return a;
    return a.isAfter(b) ? a : b;
  }
}
