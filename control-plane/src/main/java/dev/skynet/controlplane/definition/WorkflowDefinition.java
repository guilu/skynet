package dev.skynet.controlplane.definition;

import java.util.List;
import java.util.Optional;

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
}
