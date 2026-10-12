package dev.skynet.runner.provider.codex;

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
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CodexEventParserTest {

  private static final String THREAD = "0199a213-81c0-7800-8aa1-bbab2a035a53";

  @Test
  void simpleText() {
    Parsed p = parse("01-simple-text", "gpt-5.5", false);

    assertThat(p.types()).containsExactly(SESSION_STARTED, RAW, MESSAGE_RECEIVED, RESULT);
    assertThat(p.first(SESSION_STARTED).payload())
        .containsEntry("provider", "codex")
        .containsEntry("sessionId", THREAD)
        .containsEntry("model", "gpt-5.5");
    assertThat(p.first(MESSAGE_RECEIVED).payload())
        .containsEntry("messageId", "item_1")
        .containsEntry("text", "Hola. El repositorio tiene una función `add` en calc.py.");
    // 1211 sin caché × 5 + 6912 de caché × 0,50 + 64 de salida × 30, por millón.
    assertThat(p.first(RESULT).payload())
        .containsEntry("subtype", "success")
        .containsEntry("isError", false)
        .containsEntry("numTurns", 1L)
        .containsEntry("sessionId", THREAD)
        .containsEntry("usage", Map.of("input", 1211L, "output", 64L, "cacheRead", 6912L))
        .containsEntry("result", "Hola. El repositorio tiene una función `add` en calc.py.");
    assertThat((BigDecimal) p.first(RESULT).payload().get("costUsd"))
        .isEqualByComparingTo("0.011431");
    assertThat(p.parser().sawResult()).isTrue();
  }

  @Test
  void toolsAreAnnouncedOnceAndCompletedWithTheirOutput() {
    Parsed p = parse("02-tools", null, false);

    assertThat(p.of(TOOL_STARTED))
        .extracting(e -> e.payload().get("toolUseId"))
        .containsExactly("item_1", "item_3", "item_4", "item_5", "item_6", "item_7");
    assertThat(p.of(TOOL_STARTED))
        .extracting(e -> e.payload().get("name"))
        .containsExactly("Bash", "apply_patch", "Bash", "mcp__docs__search", "WebSearch", "Bash");
    assertThat(p.first(TOOL_STARTED).payload())
        .containsEntry("input", Map.of("command", "bash -lc 'cat calc.py'"));

    List<ParsedEvent> completed = p.of(TOOL_COMPLETED);
    assertThat(completed).hasSize(6);
    assertThat(completed.getFirst().payload())
        .containsEntry("isError", false)
        .containsEntry("output", "def add(a, b):\n    return a - b\n")
        .containsEntry("details", Map.of("exitCode", 0L));
    assertThat(completed.get(3).payload()).containsEntry("output", "Sin resultados");
    assertThat(completed.getLast().payload())
        .containsEntry("isError", true)
        .containsEntry("details", Map.of("exitCode", 128L));

    assertThat(p.first(FILE_CHANGED).payload())
        .containsEntry("tool", "apply_patch")
        .containsEntry("path", "calc.py")
        .containsEntry("change", "modified");
    // El razonamiento queda como RAW; la lista de tareas no se publica.
    assertThat(p.of(RAW)).hasSize(1);
    // Sin modelo no hay precio.
    assertThat(p.first(RESULT).payload()).doesNotContainKey("costUsd");
    assertThat(p.first(RESULT).payload().get("result"))
        .isEqualTo("He cambiado `a - b` por `a + b` en calc.py y la prueba pasa.");
  }

  @Test
  void aFailedTurnIsAnErrorResult() {
    Parsed p = parse("04-failed", null, false);

    assertThat(p.types()).containsExactly(SESSION_STARTED, RAW, RESULT);
    assertThat(p.first(RESULT).payload())
        .containsEntry("subtype", "error")
        .containsEntry("isError", true)
        .containsEntry(
            "errors", List.of("stream disconnected before completion: rate limit reached"));
  }

  @Test
  void anInterruptedTurnHasNoResult() {
    Parsed p = parse("05-cancelled", null, false);

    assertThat(p.types()).containsExactly(SESSION_STARTED, TOOL_STARTED);
    assertThat(p.parser().sawResult()).isFalse();
  }

  @Test
  void withASchemaTheLastMessageIsTheStructuredOutput() {
    CodexEventParser parser = new CodexEventParser(null, true, ModelPrices.defaults());
    parser.parse(
        "{\"type\":\"item.completed\",\"item\":{\"id\":\"i\",\"type\":\"agent_message\","
            + "\"text\":\"{\\\"verdict\\\":\\\"ok\\\",\\\"score\\\":3}\"}}");
    ParsedEvent result =
        parser.parse("{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":1}}").getFirst();

    assertThat(result.payload())
        .containsEntry("isError", false)
        .containsEntry("structuredOutput", Map.of("verdict", "ok", "score", 3));
  }

  @Test
  void withASchemaAMessageThatIsNotJsonIsAnError() {
    CodexEventParser parser = new CodexEventParser(null, true, ModelPrices.defaults());
    parser.parse(
        "{\"type\":\"item.completed\",\"item\":{\"id\":\"i\",\"type\":\"agent_message\","
            + "\"text\":\"Listo\"}}");
    ParsedEvent result = parser.parse("{\"type\":\"turn.completed\",\"usage\":{}}").getFirst();

    assertThat(result.payload())
        .containsEntry("subtype", "error_structured_output")
        .containsEntry("isError", true)
        .doesNotContainKey("structuredOutput");
  }

  @Test
  void longToolOutputIsTruncated() {
    CodexEventParser parser = new CodexEventParser(null, false, ModelPrices.defaults());
    String big = "x".repeat(CodexEventParser.MAX_TOOL_OUTPUT + 10);
    List<ParsedEvent> events =
        parser.parse(
            "{\"type\":\"item.completed\",\"item\":{\"id\":\"c\",\"type\":\"command_execution\","
                + "\"command\":\"ls\",\"aggregated_output\":\""
                + big
                + "\",\"exit_code\":0,\"status\":\"completed\"}}");

    assertThat(events).extracting(ParsedEvent::type).containsExactly(TOOL_STARTED, TOOL_COMPLETED);
    assertThat((String) events.getLast().payload().get("output"))
        .hasSize(CodexEventParser.MAX_TOOL_OUTPUT);
    assertThat(events.getLast().payload()).containsEntry("outputTruncated", true);
  }

  @Test
  void invalidLinesAreRaw() {
    CodexEventParser parser = new CodexEventParser(null, false, ModelPrices.defaults());

    assertThat(parser.parse("no es json")).extracting(ParsedEvent::type).containsExactly(RAW);
    assertThat(parser.parse("[1]")).extracting(ParsedEvent::type).containsExactly(RAW);
    assertThat(parser.parse("")).isEmpty();
  }

  private static Parsed parse(String fixture, String model, boolean structured) {
    CodexEventParser parser = new CodexEventParser(model, structured, ModelPrices.defaults());
    List<ParsedEvent> events = new ArrayList<>();
    for (String line : lines(fixture)) {
      events.addAll(parser.parse(line));
    }
    return new Parsed(parser, events);
  }

  static List<String> lines(String fixture) {
    try (InputStream in =
        CodexEventParserTest.class.getResourceAsStream("/codex/" + fixture + ".jsonl")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private record Parsed(CodexEventParser parser, List<ParsedEvent> events) {
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
