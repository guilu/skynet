package dev.skynet.runner.provider;

import dev.skynet.protocol.AgentEventType;
import dev.skynet.protocol.runner.QuotaSample;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** El evento {@code agent.quota} de una invocación, a partir de las muestras de su proveedor. */
public final class QuotaEvents {

  private QuotaEvents() {}

  /** Un evento con las ventanas de {@code samples}, todas del mismo proveedor. */
  public static ParsedEvent of(List<QuotaSample> samples) {
    List<Map<String, Object>> windows = new ArrayList<>();
    for (QuotaSample sample : samples) {
      Map<String, Object> window = new LinkedHashMap<>();
      window.put("bucket", sample.bucket());
      if (sample.label() != null) {
        window.put("label", sample.label());
      }
      window.put("window", sample.window());
      window.put("usedFraction", sample.usedFraction());
      if (sample.resetsAt() != null) {
        window.put("resetsAt", sample.resetsAt().getEpochSecond());
      }
      if (sample.plan() != null) {
        window.put("plan", sample.plan());
      }
      windows.add(window);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("provider", samples.getFirst().provider());
    payload.put("windows", windows);
    return new ParsedEvent(AgentEventType.QUOTA, payload);
  }
}
