package dev.skynet.runner.provider.gemini;

import static dev.skynet.protocol.AgentEventType.FILE_CHANGED;
import static dev.skynet.protocol.AgentEventType.MESSAGE_RECEIVED;
import static dev.skynet.protocol.AgentEventType.RAW;
import static dev.skynet.protocol.AgentEventType.RESULT;
import static dev.skynet.protocol.AgentEventType.SESSION_STARTED;
import static dev.skynet.protocol.AgentEventType.TOOL_COMPLETED;
import static dev.skynet.protocol.AgentEventType.TOOL_STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.protocol.AgentEventType;
import dev.skynet.runner.provider.ModelPrices;
import dev.skynet.runner.provider.ParsedEvent;
import dev.skynet.runner.provider.StructuredOutput;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GeminiEventParserTest {

  private static final String SESSION = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final StructuredOutput FIXED =
      new StructuredOutput(
          "{\"type\":\"object\",\"properties\":{\"fixed\":{\"type\":\"boolean\"}}}");

  @Test
  void simpleTextJoinsTheFragmentsOfAMessage() {
    Parsed p = parse("01-simple-text", null, null);

    assertThat(p.types()).containsExactly(SESSION_STARTED, MESSAGE_RECEIVED, RESULT);
    // "auto" es un alias: sin modelo pedido, el inicio no dice ninguno.
    assertThat(p.first(SESSION_STARTED).payload())
        .containsEntry("provider", "gemini")
        .containsEntry("sessionId", SESSION)
        .doesNotContainKey("model");
    assertThat(p.first(MESSAGE_RECEIVED).payload())
        .containsEntry("messageId", "msg_1")
        .containsEntry("text", "Hola. El repositorio tiene una función `add` en calc.py.");
    // 2050 sin caché × 1,25 + 7000 de caché × 0,125 + 70 de salida × 10, por millón.
    assertThat(p.first(RESULT).payload())
        .containsEntry("subtype", "success")
        .containsEntry("isError", false)
        .containsEntry("sessionId", SESSION)
        .containsEntry("usage", Map.of("input", 2050L, "output", 70L, "cacheRead", 7000L))
        .containsEntry("models", List.of("gemini-2.5-pro"))
        .containsEntry("result", "Hola. El repositorio tiene una función `add` en calc.py.");
    assertThat((BigDecimal) p.first(RESULT).payload().get("costUsd"))
        .isEqualByComparingTo("0.0041375");
    assertThat(p.parser().sawResult()).isTrue();
  }

  @Test
  void toolsAndTheirResults() {
    Parsed p = parse("02-tools", "gemini-2.5-pro", null);

    assertThat(p.first(SESSION_STARTED).payload()).containsEntry("model", "gemini-2.5-pro");
    assertThat(p.types())
        .containsExactly(
            SESSION_STARTED,
            MESSAGE_RECEIVED,
            TOOL_STARTED,
            TOOL_COMPLETED,
            TOOL_STARTED,
            TOOL_COMPLETED,
            FILE_CHANGED,
            TOOL_STARTED,
            TOOL_COMPLETED,
            TOOL_STARTED,
            TOOL_COMPLETED,
            MESSAGE_RECEIVED,
            RESULT);
    assertThat(p.of(TOOL_STARTED))
        .extracting(e -> e.payload().get("name"))
        .containsExactly("read_file", "replace", "Bash", "Bash");
    assertThat(p.of(TOOL_STARTED).get(2).payload().get("input"))
        .isEqualTo(Map.of("command", "python3 -m unittest -q", "description", "Pasa los tests"));
    assertThat(p.first(TOOL_COMPLETED).payload())
        .containsEntry("toolUseId", "read_file-1")
        .containsEntry("name", "read_file")
        .containsEntry("isError", false)
        .containsEntry("output", "def add(a, b):\n    return a - b\n");
    assertThat(p.of(TOOL_COMPLETED).getLast().payload())
        .containsEntry("name", "Bash")
        .containsEntry("isError", true)
        .containsEntry("output", "Tool execution denied by policy.")
        .containsEntry("details", Map.of("errorType", "policy_violation"));
    assertThat(p.first(FILE_CHANGED).payload())
        .containsEntry("tool", "replace")
        .containsEntry("path", "calc.py")
        .containsEntry("change", "modified");
    // Con dos modelos, cada uno a su precio: 0,019875 + 0,000905.
    assertThat((BigDecimal) p.first(RESULT).payload().get("costUsd"))
        .isEqualByComparingTo("0.02078");
    assertThat(p.first(RESULT).payload().get("result"))
        .isEqualTo("He cambiado `a - b` por `a + b` en calc.py y la prueba pasa.");
  }

  @Test
  void aTurnLimitIsAnErrorResult() {
    Parsed p = parse("04-turn-limit", null, null);

    assertThat(p.types()).containsExactly(SESSION_STARTED, MESSAGE_RECEIVED, RAW, RESULT);
    assertThat(p.first(RESULT).payload())
        .containsEntry("subtype", "error")
        .containsEntry("isError", true)
        .doesNotContainKey("costUsd");
    assertThat((List<?>) p.first(RESULT).payload().get("errors"))
        .singleElement()
        .asString()
        .startsWith("Reached max session turns");
  }

  @Test
  void anInterruptedRunHasNoResult() {
    Parsed p = parse("05-cancelled", null, null);

    assertThat(p.types()).containsExactly(SESSION_STARTED, TOOL_STARTED);
    assertThat(p.parser().sawResult()).isFalse();
  }

  @Test
  void theStructuredOutputIsTheJsonAtTheEndOfTheLastMessage() {
    GeminiEventParser parser = new GeminiEventParser(null, FIXED, ModelPrices.defaults());
    parser.parse(message("Listo.\n```json\n{\"fixed\": "));
    parser.parse(message("true}\n```"));
    ParsedEvent result = parser.parse(result()).getLast();

    assertThat(result.payload())
        .containsEntry("isError", false)
        .containsEntry("structuredOutput", Map.of("fixed", true));
  }

  @Test
  void aStructuredOutputThatDoesNotMatchTheSchemaIsAnError() {
    GeminiEventParser parser = new GeminiEventParser(null, FIXED, ModelPrices.defaults());
    parser.parse(message("{\"fixed\": \"sí\"}"));
    ParsedEvent result = parser.parse(result()).getLast();

    assertThat(result.payload())
        .containsEntry("subtype", "error_structured_output")
        .containsEntry("isError", true)
        .containsEntry(
            "errors",
            List.of("La salida no cumple el schema de `output`: `fixed` debe ser boolean"))
        .doesNotContainKey("structuredOutput");
  }

  @Test
  void aMissingStructuredOutputIsAnError() {
    GeminiEventParser parser = new GeminiEventParser(null, FIXED, ModelPrices.defaults());
    parser.parse(message("He terminado."));

    assertThat(parser.parse(result()).getLast().payload())
        .containsEntry("subtype", "error_structured_output")
        .containsEntry("errors", List.of("El agente no terminó con un objeto JSON"));
  }

  @Test
  void invalidLinesAreRaw() {
    GeminiEventParser parser = new GeminiEventParser(null, null, ModelPrices.defaults());

    assertThat(parser.parse("no es json")).extracting(ParsedEvent::type).containsExactly(RAW);
    assertThat(parser.parse("[1]")).extracting(ParsedEvent::type).containsExactly(RAW);
    assertThat(parser.parse("")).isEmpty();
  }

  private static String message(String text) {
    return JsonMapper.builder()
        .build()
        .writeValueAsString(
            Map.of("type", "message", "role", "assistant", "delta", true, "content", text));
  }

  private static String result() {
    return "{\"type\":\"result\",\"status\":\"success\",\"stats\":{}}";
  }

  private static Parsed parse(String fixture, String model, StructuredOutput structured) {
    GeminiEventParser parser = new GeminiEventParser(model, structured, ModelPrices.defaults());
    List<ParsedEvent> events = new ArrayList<>();
    for (String line : lines(fixture)) {
      events.addAll(parser.parse(line));
    }
    return new Parsed(parser, events);
  }

  static List<String> lines(String fixture) {
    try (InputStream in =
        GeminiEventParserTest.class.getResourceAsStream("/gemini/" + fixture + ".jsonl")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private record Parsed(GeminiEventParser parser, List<ParsedEvent> events) {
    List<AgentEventType> types() {
      return events.stream().map(ParsedEvent::type).toList();
    }

    List<ParsedEvent> of(AgentEventType type) {
      return events.stream().filter(e -> e.type() == type).toList();
    }

    ParsedEvent first(AgentEventType type) {
      return of(type).getFirst();
    }
  }
}
