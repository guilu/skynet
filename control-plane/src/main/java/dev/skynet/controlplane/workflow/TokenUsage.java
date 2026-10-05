package dev.skynet.controlplane.workflow;

import java.util.Map;

/** Tokens de entrada, salida y caché (lectura y escritura), tal y como los envía el runner. */
record TokenUsage(Long input, Long output, Long cacheRead, Long cacheCreation) {

  static final TokenUsage NONE = new TokenUsage(null, null, null, null);

  static TokenUsage of(Object usage) {
    if (!(usage instanceof Map<?, ?> m)) {
      return NONE;
    }
    return new TokenUsage(
        longValue(m.get("input")),
        longValue(m.get("output")),
        longValue(m.get("cacheRead")),
        longValue(m.get("cacheCreation")));
  }

  private static Long longValue(Object value) {
    return value instanceof Number n ? n.longValue() : null;
  }
}
