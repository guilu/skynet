package dev.skynet.runner.provider.antigravity;

import static dev.skynet.protocol.AgentEventType.FILE_CHANGED;
import static dev.skynet.protocol.AgentEventType.MESSAGE_RECEIVED;
import static dev.skynet.protocol.AgentEventType.PERMISSION_DENIED;
import static dev.skynet.protocol.AgentEventType.RAW;
import static dev.skynet.protocol.AgentEventType.RESULT;
import static dev.skynet.protocol.AgentEventType.SESSION_STARTED;
import static dev.skynet.protocol.AgentEventType.TOOL_COMPLETED;
import static dev.skynet.protocol.AgentEventType.TOOL_STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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

/** Con las salidas de agy 1.4.2 grabadas en la máquina de Diego ({@code fixtures/agy}). */
class AntigravityEventParserTest {

  @Test
  void simpleTextJoinsTheFragmentsOfAStep() {
    Parsed p = parse("01-simple-text", null, false);

    assertThat(p.types()).containsExactly(SESSION_STARTED, MESSAGE_RECEIVED, RESULT);
    assertThat(p.first(SESSION_STARTED).payload())
        .containsEntry("provider", "antigravity")
        .containsEntry("sessionId", "e105fbdd-d1b0-4f52-a54b-d50b06287642")
        .containsEntry("permissionMode", "request-review")
        .doesNotContainKey("model");
    assertThat(p.first(MESSAGE_RECEIVED).payload())
        .containsEntry("messageId", "step_1")
        .containsEntry("text", "hola");
    // Sin modelo pedido no se sabe cuál usó agy: no hay coste.
    assertThat(p.first(RESULT).payload())
        .containsEntry("subtype", "success")
        .containsEntry("isError", false)
        .containsEntry("numTurns", 1L)
        .containsEntry("sessionId", "e105fbdd-d1b0-4f52-a54b-d50b06287642")
        .containsEntry("usage", Map.of("input", 11773L, "output", 5L, "cacheRead", 0L))
        .containsEntry("result", "hola")
        .doesNotContainKey("costUsd");
    assertThat(p.parser().sawResult()).isTrue();
    assertThat(p.parser().conversationId()).isEqualTo("e105fbdd-d1b0-4f52-a54b-d50b06287642");
  }

  @Test
  void withAModelTheResultCarriesItsCost() {
    Parsed p = parse("01-simple-text", "gemini-3-flash", false);

    // 11773 de entrada × 0,50 + 5 de salida × 3, por millón.
    assertThat((BigDecimal) p.first(RESULT).payload().get("costUsd"))
        .isEqualByComparingTo("0.0059015");
    assertThat(p.first(SESSION_STARTED).payload()).containsEntry("model", "gemini-3-flash");
  }

  @Test
  void toolsAndTheFilesTheyChange() {
    Parsed p = parse("02-tools", null, false);

    assertThat(p.types())
        .containsExactly(
            SESSION_STARTED,
            TOOL_STARTED,
            TOOL_COMPLETED,
            TOOL_STARTED,
            TOOL_COMPLETED,
            FILE_CHANGED,
            MESSAGE_RECEIVED,
            RESULT);
    assertThat(p.first(TOOL_STARTED).payload())
        .containsEntry("toolUseId", "step_2")
        .containsEntry("name", "view_file")
        .containsEntry("input", Map.of("AbsolutePath", "/tmp/prueba/calc.py"));
    assertThat(p.first(TOOL_COMPLETED).payload())
        .containsEntry("toolUseId", "step_2")
        .containsEntry("isError", false)
        .containsEntry("output", "2 lines, 28 bytes");
    assertThat(p.first(FILE_CHANGED).payload())
        .containsEntry("toolUseId", "step_4")
        .containsEntry("tool", "replace_file_content")
        .containsEntry("path", "/tmp/prueba/calc.py")
        .containsEntry("change", "modified");
    assertThat((String) p.first(MESSAGE_RECEIVED).payload().get("text"))
        .startsWith("He leído [calc.py]")
        .endsWith("en lugar de `a - b`.");
    // El razonamiento se cobra como salida.
    assertThat(p.first(RESULT).payload())
        .containsEntry("usage", Map.of("input", 16498L, "output", 349L, "cacheRead", 19733L));
  }

  @Test
  void aResumedConversationKeepsItsId() {
    Parsed p = parse("03-resume", null, false);

    assertThat(p.types()).containsExactly(SESSION_STARTED, MESSAGE_RECEIVED, RESULT);
    assertThat(p.first(RESULT).payload())
        .containsEntry("sessionId", "8c121a4f-8ae9-4b1d-9d7f-62437f5ed690")
        .containsEntry("numTurns", 2L);
  }

  @Test
  void theStructuredOutputComesInTheResult() {
    Parsed p = parse("04-schema", null, true);

    assertThat(p.first(RESULT).payload())
        .containsEntry("isError", false)
        .containsEntry("structuredOutput", Map.of("ok", true));
  }

  @Test
  void aMissingStructuredOutputIsAnError() {
    Parsed p = parse("01-simple-text", null, true);

    assertThat(p.first(RESULT).payload())
        .containsEntry("subtype", "error_structured_output")
        .containsEntry("isError", true)
        .containsEntry("errors", List.of("Antigravity CLI no devolvió la salida estructurada"));
  }

  @Test
  void aCommandWithoutPermissionIsDenied() {
    Parsed p = parse("05-denied", null, false);

    assertThat(p.types())
        .containsExactly(SESSION_STARTED, TOOL_STARTED, TOOL_COMPLETED, PERMISSION_DENIED, RESULT);
    assertThat(p.first(TOOL_STARTED).payload())
        .containsEntry("name", "Bash")
        .containsEntry("input", Map.of("command", "ls -la"));
    assertThat(p.first(PERMISSION_DENIED).payload())
        .containsEntry("toolName", "RunCommand")
        .containsEntry("reason", "command");
    assertThat(p.first(RESULT).payload())
        .containsEntry("isError", false)
        .doesNotContainKey("result");
  }

  @Test
  void aCancelledRunHasNoResult() {
    Parsed p = parse("06-cancelled", null, false);

    assertThat(p.types()).doesNotContain(RESULT);
    assertThat(p.parser().sawResult()).isFalse();
  }

  @Test
  void theTokensOfEachStepTravelLiveSoACancelledRunKeepsThem() {
    Parsed p = parse("06-cancelled", null, false);

    // Los pasos 1 y 3 del agente no dicen nada: sus tokens van con la herramienta siguiente.
    assertThat(p.events())
        .filteredOn(e -> e.payload().containsKey("usage"))
        .extracting(
            ParsedEvent::type, e -> e.payload().get("toolUseId"), e -> e.payload().get("usage"))
        .containsExactly(
            tuple(TOOL_STARTED, "step_2", Map.of("input", 11783L, "output", 90L, "cacheRead", 0L)),
            tuple(
                TOOL_STARTED,
                "step_4",
                Map.of("input", 2304L, "output", 172L, "cacheRead", 9736L)));
  }

  @Test
  void aFailedRunIsAnErrorResultAndUnknownLinesAreRaw() {
    AntigravityEventParser parser = new AntigravityEventParser(null, false, ModelPrices.defaults());

    assertThat(parser.parse("{\"event\":\"nuevo\"}").getFirst().type()).isEqualTo(RAW);
    ParsedEvent result =
        parser
            .parse("{\"event\":\"result\",\"result\":{\"status\":\"ERROR\",\"response\":\"\"}}")
            .getFirst();
    assertThat(result.payload())
        .containsEntry("subtype", "error")
        .containsEntry("isError", true)
        .containsEntry("errors", List.of("Antigravity CLI terminó con estado ERROR"));
  }

  private static Parsed parse(String fixture, String model, boolean structured) {
    AntigravityEventParser parser =
        new AntigravityEventParser(model, structured, ModelPrices.defaults());
    List<ParsedEvent> events = new ArrayList<>();
    lines(fixture).forEach(l -> events.addAll(parser.parse(l)));
    return new Parsed(parser, events);
  }

  static List<String> lines(String fixture) {
    try (InputStream in =
        AntigravityEventParserTest.class.getResourceAsStream("/agy/" + fixture + ".jsonl")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private record Parsed(AntigravityEventParser parser, List<ParsedEvent> events) {
    List<AgentEventType> types() {
      return events.stream().map(ParsedEvent::type).toList();
    }

    ParsedEvent first(AgentEventType type) {
      return events.stream().filter(e -> e.type() == type).findFirst().orElseThrow();
    }
  }
}
