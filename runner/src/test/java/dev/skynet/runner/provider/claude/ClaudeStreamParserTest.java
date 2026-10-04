package dev.skynet.runner.provider.claude;

import static dev.skynet.protocol.AgentEventType.FILE_CHANGED;
import static dev.skynet.protocol.AgentEventType.MESSAGE_RECEIVED;
import static dev.skynet.protocol.AgentEventType.PERMISSION_DENIED;
import static dev.skynet.protocol.AgentEventType.RATE_LIMIT;
import static dev.skynet.protocol.AgentEventType.RAW;
import static dev.skynet.protocol.AgentEventType.RESULT;
import static dev.skynet.protocol.AgentEventType.SESSION_STARTED;
import static dev.skynet.protocol.AgentEventType.TOOL_COMPLETED;
import static dev.skynet.protocol.AgentEventType.TOOL_STARTED;
import static dev.skynet.protocol.AgentEventType.VCS_CHANGED;
import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.protocol.AgentEventType;
import dev.skynet.runner.provider.ParsedEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClaudeStreamParserTest {

  @Test
  void simpleText() {
    Parsed p = parse("01-simple-text");

    assertThat(p.types()).containsExactly(SESSION_STARTED, MESSAGE_RECEIVED, RESULT);
    assertThat(p.first(SESSION_STARTED).payload())
        .containsEntry("sessionId", "e1e75e9c-2b3a-4287-ae55-186ae1babc98")
        .containsEntry("model", "claude-opus-5-5")
        .containsEntry("providerVersion", "2.1.288")
        .containsEntry("provider", "claude-code");
    assertThat(p.first(MESSAGE_RECEIVED).payload())
        .containsEntry("text", "pong")
        .containsEntry("messageId", "msg_011CffQQRN49vB1wwEphzi3i");
    assertThat(p.first(RESULT).payload())
        .containsEntry("subtype", "success")
        .containsEntry("isError", false)
        .containsEntry("numTurns", 1L)
        .containsEntry("costUsdCumulative", new BigDecimal("0.048965"))
        .containsEntry("result", "pong");
    assertThat(p.parser().sawResult()).isTrue();
    assertThat(p.parser().sessionId()).isEqualTo("e1e75e9c-2b3a-4287-ae55-186ae1babc98");
  }

  @Test
  void toolsEditAndCommit() {
    Parsed p = parse("02-tools");

    assertThat(p.types())
        .containsExactly(
            SESSION_STARTED,
            TOOL_STARTED,
            TOOL_COMPLETED,
            TOOL_STARTED,
            TOOL_COMPLETED,
            FILE_CHANGED,
            TOOL_STARTED,
            // El CLI anuncia el commit antes del resultado del Bash que lo hizo.
            VCS_CHANGED,
            TOOL_COMPLETED,
            MESSAGE_RECEIVED,
            RESULT);

    List<ParsedEvent> started = p.all(TOOL_STARTED);
    assertThat(started)
        .extracting(e -> e.payload().get("name"))
        .containsExactly("Read", "Edit", "Bash");
    assertThat(started.get(0).payload().get("input"))
        .isEqualTo(Map.of("file_path", "/workspaces/demo/calc.py"));

    List<ParsedEvent> completed = p.all(TOOL_COMPLETED);
    assertThat(completed)
        .extracting(e -> e.payload().get("name"))
        .containsExactly("Read", "Edit", "Bash");
    assertThat(completed).allSatisfy(e -> assertThat(e.payload()).containsEntry("isError", false));
    assertThat(details(completed.get(0))).containsEntry("filePath", "/workspaces/demo/calc.py");
    assertThat(details(completed.get(2)))
        .containsEntry("interrupted", false)
        .containsKey("gitOperation");
    assertThat((String) details(completed.get(2)).get("stdout")).contains("fix add");

    ParsedEvent file = p.first(FILE_CHANGED);
    assertThat(file.payload())
        .containsEntry("path", "/workspaces/demo/calc.py")
        .containsEntry("tool", "Edit")
        .containsEntry("change", "modified")
        .containsEntry("toolUseId", completed.get(1).payload().get("toolUseId"));
    assertThat(file.payload().get("patch")).asInstanceOf(InstanceOfAssertFactories.LIST).hasSize(1);

    assertThat(p.first(VCS_CHANGED).payload())
        .containsEntry("kind", "commit")
        .containsEntry("branch", "master");
    assertThat(p.first(RESULT).payload())
        .containsEntry("numTurns", 4L)
        .containsEntry("costUsdCumulative", new BigDecimal("0.0573992"));
  }

  @Test
  void resumeAndForkReportCumulativeCost() {
    assertThat(parse("03-resume").first(RESULT).payload())
        .containsEntry("costUsdCumulative", new BigDecimal("0.08147199999999999"));
    Parsed fork = parse("04-fork");
    assertThat(fork.types()).containsExactly(SESSION_STARTED, MESSAGE_RECEIVED, RESULT);
    assertThat((BigDecimal) fork.first(RESULT).payload().get("costUsdCumulative"))
        .isEqualTo(new BigDecimal("0.08665219999999998"));
  }

  @Test
  void maxTurnsIsAnErrorResult() {
    Parsed p = parse("05-max-turns");

    assertThat(p.first(RESULT).payload())
        .containsEntry("subtype", "error_max_turns")
        .containsEntry("isError", true)
        .containsEntry("terminalReason", "max_turns")
        .containsEntry("errors", List.of("Reached maximum number of turns (1)"))
        .doesNotContainKey("result");
    // Los bloques thinking no producen eventos.
    assertThat(p.types()).doesNotContain(RAW, MESSAGE_RECEIVED);
  }

  @Test
  void permissionDenialsAreVisibleButTheRunSucceeds() {
    Parsed p = parse("06-permission-denied");

    assertThat(p.all(PERMISSION_DENIED))
        .extracting(e -> e.payload().get("toolName"))
        .containsExactly("Bash", "Write");
    assertThat(p.all(PERMISSION_DENIED))
        .allSatisfy(e -> assertThat(e.payload()).containsEntry("reason", "mode"));
    assertThat(p.all(TOOL_COMPLETED))
        .allSatisfy(e -> assertThat(e.payload()).containsEntry("isError", true));
    // Una herramienta de escritura denegada no cambia ningún archivo.
    assertThat(p.types()).doesNotContain(FILE_CHANGED, RAW);
    assertThat(p.first(RESULT).payload())
        .containsEntry("subtype", "success")
        .containsEntry("permissionDenials", 2);
  }

  @Test
  void cancelledSessionHasNoResult() {
    Parsed p = parse("07-cancelled");

    assertThat(p.types()).containsExactly(SESSION_STARTED);
    assertThat(p.parser().sawResult()).isFalse();
  }

  @Test
  void budgetExceeded() {
    assertThat(parse("08-budget-exceeded").first(RESULT).payload())
        .containsEntry("subtype", "error_max_budget_usd")
        .containsEntry("terminalReason", "budget_exhausted")
        .containsEntry("isError", true);
  }

  @Test
  void structuredOutput() {
    assertThat(parse("09-json-schema").first(RESULT).payload().get("structuredOutput"))
        .asInstanceOf(InstanceOfAssertFactories.MAP)
        .containsEntry("kind", "bug")
        .containsKey("summary");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "01-simple-text",
        "02-tools",
        "03-resume",
        "04-fork",
        "05-max-turns",
        "06-permission-denied",
        "07-cancelled",
        "08-budget-exceeded",
        "09-json-schema"
      })
  void everyFixtureParsesWithoutRawEventsAndStartsWithTheSession(String fixture) {
    Parsed p = parse(fixture);

    assertThat(p.types()).first().isEqualTo(SESSION_STARTED);
    assertThat(p.types()).doesNotContain(RAW);
    assertThat(p.types()).filteredOn(t -> t == RESULT).hasSizeLessThanOrEqualTo(1);
    // Cada herramienta que termina se abrió antes, con el mismo nombre.
    assertThat(p.all(TOOL_COMPLETED)).allSatisfy(e -> assertThat(e.payload()).containsKey("name"));
  }

  @Test
  void unknownOrInvalidLinesBecomeRawEvents() {
    ClaudeStreamParser parser = new ClaudeStreamParser();

    assertThat(parser.parse("not json"))
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.type()).isEqualTo(RAW);
              assertThat(e.payload())
                  .containsEntry("line", "not json")
                  .containsEntry("error", "invalid_json");
            });
    assertThat(parser.parse("{\"type\":\"telemetry\",\"x\":1}"))
        .singleElement()
        .satisfies(
            e ->
                assertThat(e.payload().get("line")).isEqualTo(Map.of("type", "telemetry", "x", 1)));
    assertThat(parser.parse("{\"type\":\"system\",\"subtype\":\"compacting\"}"))
        .extracting(ParsedEvent::type)
        .containsExactly(RAW);
    assertThat(parser.parse("[1,2]")).extracting(ParsedEvent::type).containsExactly(RAW);
    assertThat(parser.parse("")).isEmpty();
  }

  @Test
  void rateLimitOnlyWhenNotAllowed() {
    ClaudeStreamParser parser = new ClaudeStreamParser();

    assertThat(
            parser.parse(
                "{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"allowed\"}}"))
        .isEmpty();
    assertThat(
            parser.parse(
                "{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"rejected\","
                    + "\"rateLimitType\":\"five_hour\",\"resetsAt\":1791046200}}"))
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.type()).isEqualTo(RATE_LIMIT);
              assertThat(e.payload())
                  .containsEntry("status", "rejected")
                  .containsEntry("limitType", "five_hour")
                  .containsEntry("resetsAt", 1791046200L);
            });
  }

  @Test
  void longToolOutputIsTruncated() {
    ClaudeStreamParser parser = new ClaudeStreamParser();
    String big = "x".repeat(ClaudeStreamParser.MAX_TOOL_OUTPUT + 10);

    ParsedEvent event =
        parser
            .parse(
                "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\","
                    + "\"tool_use_id\":\"t1\",\"content\":[{\"type\":\"text\",\"text\":\""
                    + big
                    + "\"}]}]}}")
            .getFirst();

    assertThat((String) event.payload().get("output")).hasSize(ClaudeStreamParser.MAX_TOOL_OUTPUT);
    assertThat(event.payload()).containsEntry("outputTruncated", true);
  }

  @Test
  void writeCreatingAFileIsReportedAsCreated() {
    ClaudeStreamParser parser = new ClaudeStreamParser();
    parser.parse(
        "{\"type\":\"assistant\",\"message\":{\"id\":\"m1\",\"content\":[{\"type\":\"tool_use\","
            + "\"id\":\"t1\",\"name\":\"Write\",\"input\":{\"file_path\":\"/w/new.txt\"}}]}}");

    List<ParsedEvent> events =
        parser.parse(
            "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\","
                + "\"tool_use_id\":\"t1\",\"content\":\"ok\"}]},"
                + "\"tool_use_result\":{\"type\":\"create\",\"filePath\":\"/w/new.txt\","
                + "\"content\":\"hola\",\"structuredPatch\":[]}}");

    assertThat(events).extracting(ParsedEvent::type).containsExactly(TOOL_COMPLETED, FILE_CHANGED);
    assertThat(events.get(1).payload())
        .containsEntry("path", "/w/new.txt")
        .containsEntry("change", "created");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> details(ParsedEvent e) {
    return (Map<String, Object>) e.payload().get("details");
  }

  private static Parsed parse(String fixture) {
    ClaudeStreamParser parser = new ClaudeStreamParser();
    List<ParsedEvent> events = new ArrayList<>();
    try (InputStream in =
            ClaudeStreamParserTest.class.getResourceAsStream("/claude/" + fixture + ".ndjson");
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      reader.lines().forEach(line -> events.addAll(parser.parse(line)));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return new Parsed(parser, events);
  }

  private record Parsed(ClaudeStreamParser parser, List<ParsedEvent> events) {

    List<AgentEventType> types() {
      return events.stream().map(ParsedEvent::type).toList();
    }

    List<ParsedEvent> all(AgentEventType type) {
      return events.stream().filter(e -> e.type() == type).toList();
    }

    ParsedEvent first(AgentEventType type) {
      return all(type).getFirst();
    }
  }
}
