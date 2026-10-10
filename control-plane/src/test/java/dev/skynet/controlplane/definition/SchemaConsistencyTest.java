package dev.skynet.controlplane.definition;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * El JSON Schema que usa el editor de la web admite las mismas claves y tipos que el parser del
 * control plane.
 */
class SchemaConsistencyTest {

  private final JsonNode schema = load();

  @Test
  void rootKeys() {
    assertThat(names(schema.path("properties"))).isEqualTo(DefinitionParser.ROOT_KEYS);
  }

  @Test
  void inputAgentAndLimitKeys() {
    JsonNode defs = schema.path("$defs");
    assertThat(names(defs.path("input").path("properties"))).isEqualTo(DefinitionParser.INPUT_KEYS);
    assertThat(names(defs.path("agent").path("properties"))).isEqualTo(DefinitionParser.AGENT_KEYS);
    assertThat(names(defs.path("agent").path("properties").path("limits").path("properties")))
        .isEqualTo(DefinitionParser.LIMIT_KEYS);
  }

  @Test
  void stageKeysAndTypes() {
    JsonNode stage = schema.path("$defs").path("stage").path("properties");
    Set<String> expected = new HashSet<>(DefinitionParser.STAGE_KEYS);
    expected.addAll(DefinitionParser.FUTURE_STAGE_KEYS.keySet());
    assertThat(names(stage)).isEqualTo(expected);
    Set<String> types = new HashSet<>();
    stage.path("type").path("enum").forEach(t -> types.add(t.asString()));
    assertThat(types)
        .isEqualTo(new HashSet<>(Arrays.stream(StageType.values()).map(StageType::yaml).toList()));
  }

  @Test
  void inputTypesAndPermissionModes() {
    JsonNode defs = schema.path("$defs");
    Set<String> inputTypes = new HashSet<>();
    defs.path("input")
        .path("properties")
        .path("type")
        .path("enum")
        .forEach(t -> inputTypes.add(t.asString()));
    assertThat(inputTypes).isEqualTo(new HashSet<>(InputDefinition.TYPES));
    Set<String> modes = new HashSet<>();
    defs.path("agent")
        .path("properties")
        .path("permissionMode")
        .path("enum")
        .forEach(t -> modes.add(t.asString()));
    assertThat(modes)
        .isEqualTo(new HashSet<>(dev.skynet.controlplane.project.AgentPolicy.PERMISSION_MODES));
  }

  private static Set<String> names(JsonNode object) {
    return new HashSet<>(object.propertyNames());
  }

  private static JsonNode load() {
    try (InputStream in =
        SchemaConsistencyTest.class.getResourceAsStream(
            "/dev/skynet/protocol/workflow-definition.schema.json")) {
      return JsonMapper.builder().build().readTree(in);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
