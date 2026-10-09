package dev.skynet.controlplane.settings;

import dev.skynet.controlplane.shared.TimeSource;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** La apariencia guardada en {@code app_setting}, bajo la clave {@code appearance}. */
@Service
class AppearanceSettings {

  private static final String KEY = "appearance";

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final TimeSource time;

  AppearanceSettings(JdbcClient jdbc, ObjectMapper json, TimeSource time) {
    this.jdbc = jdbc;
    this.json = json;
    this.time = time;
  }

  @Transactional(readOnly = true)
  Appearance get() {
    return jdbc.sql("SELECT value, updated_at FROM app_setting WHERE key = ?")
        .param(KEY)
        .query(
            (rs, row) ->
                new Appearance(
                    json.readValue(rs.getString("value"), Palette.class),
                    rs.getTimestamp("updated_at").toInstant()))
        .optional()
        .orElse(new Appearance(Palette.DEFAULT, null));
  }

  @Transactional
  Appearance save(Palette colors) {
    Palette palette = colors.normalized();
    Instant now = time.now();
    jdbc.sql(
            "INSERT INTO app_setting (key, value, updated_at) VALUES (?, ?::jsonb, ?)"
                + " ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value,"
                + " updated_at = EXCLUDED.updated_at")
        .params(KEY, json.writeValueAsString(palette), Timestamp.from(now))
        .update();
    return new Appearance(palette, now);
  }
}
