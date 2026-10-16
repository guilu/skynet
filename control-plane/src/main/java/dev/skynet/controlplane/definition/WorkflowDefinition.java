package dev.skynet.controlplane.definition;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Un workflow ya leído del YAML. Solo existe si el documento tiene la forma básica (una clave y
 * fases); puede seguir teniendo errores, que van en {@link Validation}.
 *
 * @param key clave del workflow ({@code id} en el YAML)
 * @param name nombre para mostrar, o {@code null}
 * @param inputs datos que se piden al lanzar, en el orden del YAML
 * @param agents agentes con nombre que usan las fases, en el orden del YAML
 * @param stages fases, en el orden del YAML
 */
public record WorkflowDefinition(
    String key,
    String name,
    String description,
    List<InputDefinition> inputs,
    List<AgentDefinition> agents,
    List<StageDefinition> stages) {

  public WorkflowDefinition {
    inputs = List.copyOf(inputs);
    agents = List.copyOf(agents);
    stages = List.copyOf(stages);
  }

  public Optional<AgentDefinition> agent(String name) {
    return agents.stream().filter(a -> a.name().equals(name)).findFirst();
  }

  public Optional<StageDefinition> stage(String id) {
    return stages.stream().filter(s -> s.id().equals(id)).findFirst();
  }

  /**
   * La fase cuyo agente corrige al pedir cambios en la puerta {@code id}: la de su {@code revise}
   * o, si no lo tiene, su única dependencia {@code agent}; {@code null} si no hay a quién pedirlos.
   */
  public String revise(String id) {
    StageDefinition gate = stage(id).orElse(null);
    if (gate == null || gate.approval() == null) {
      return null;
    }
    if (gate.approval().revise() != null) {
      return gate.approval().revise();
    }
    List<String> agents =
        gate.dependsOn().stream()
            .map(StageDefinition.Dependency::stage)
            .filter(d -> stage(d).map(s -> s.type() == StageType.AGENT).orElse(false))
            .toList();
    return agents.size() == 1 ? agents.getFirst() : null;
  }

  /** Las fases que dependen directamente de {@code id}, en el orden del YAML. */
  public List<String> dependents(String id) {
    return stages.stream()
        .filter(s -> s.dependsOn().stream().anyMatch(d -> d.stage().equals(id)))
        .map(StageDefinition::id)
        .toList();
  }

  private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*(stages\\.[^{}\\s]+)\\s*}}");

  /**
   * Los datos de otras fases que lee el prompt de la fase {@code id} (o los de sus hijos, o la
   * acción de una puerta de aprobación), sin {@code stages.}: {@code spec.output.summary}, {@code
   * tests.exitCode}. En el orden en que aparecen y sin repetir.
   */
  public List<String> reads(String id) {
    StageDefinition stage = stage(id).orElse(null);
    if (stage == null) {
      return List.of();
    }
    List<String> prompts = new ArrayList<>();
    if (stage.approval() != null) {
      prompts.add(stage.approval().action());
    } else if (stage.children().isEmpty()) {
      prompts.add(promptOf(stage.prompt(), stage.agent()));
    } else {
      stage.children().forEach(c -> prompts.add(promptOf(c.prompt(), c.agent())));
    }
    Set<String> reads = new LinkedHashSet<>();
    for (String prompt : prompts) {
      if (prompt == null) {
        continue;
      }
      Matcher matcher = VARIABLE.matcher(prompt);
      while (matcher.find()) {
        String variable = matcher.group(1);
        if (Condition.promptPath(variable) != null) {
          reads.add(variable.substring("stages.".length()));
        }
      }
    }
    return List.copyOf(reads);
  }

  private String promptOf(String prompt, String agent) {
    if (prompt != null || agent == null) {
      return prompt;
    }
    return agent(agent).map(AgentDefinition::prompt).orElse(null);
  }
}
