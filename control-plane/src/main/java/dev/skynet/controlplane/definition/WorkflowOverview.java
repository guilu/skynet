package dev.skynet.controlplane.definition;

import dev.skynet.protocol.runner.VerificationCheck;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lo que una tarjeta de workflow cuenta de su definición (WF-B): cuántas fases tiene, qué te pide,
 * quién trabaja y qué entrega. Se calcula al leer, del {@link WorkflowDefinition} ya parseado.
 *
 * @param stages fases de primer nivel
 * @param parallelChildren agentes que corren a la vez dentro de fases {@code parallel}, sumados
 * @param gates fases {@code human-approval}: las que te piden aprobar
 * @param judges agentes distintos que juzgan en fases {@code verification}
 * @param providers proveedores de los agentes que trabajan o juzgan, por orden de aparición, sin
 *     repetir; {@code repository} cuando una fase de agente no dice agente y usa el del repositorio
 * @param fallbackProviders proveedores de reserva que no están ya en {@code providers}
 * @param lanes macrofases con alguna fase, en el orden de los carriles del grafo
 * @param inputs datos que se piden al lanzar
 * @param delivers tiene una fase {@code github-pr}: entrega por PR
 * @param readOnly ningún agente puede cambiar el repositorio: todos los agentes de sus fases {@code
 *     agent} y {@code parallel} van en {@code plan} o {@code dontAsk}, y no hay fases {@code
 *     command}, de entrega ni comprobaciones {@code commit} o {@code diff}
 */
public record WorkflowOverview(
    int stages,
    int parallelChildren,
    int gates,
    int judges,
    List<String> providers,
    List<String> fallbackProviders,
    List<String> lanes,
    List<Input> inputs,
    boolean delivers,
    boolean readOnly) {

  /** Proveedor de una fase de agente sin agente: el que diga la política del repositorio. */
  public static final String REPOSITORY = "repository";

  private static final Set<String> READ_ONLY_MODES = Set.of("plan", "dontAsk");
  private static final Set<StageType> WRITING_TYPES =
      Set.of(
          StageType.COMMAND,
          StageType.GITHUB_PR,
          StageType.GITHUB_CHECK,
          StageType.GITHUB_MERGE,
          StageType.MERGE,
          StageType.DEPLOY);
  private static final Set<String> WRITING_CHECKS =
      Set.of(VerificationCheck.COMMIT, VerificationCheck.DIFF);

  /** Un dato que se pide al lanzar. */
  public record Input(String name, boolean required) {}

  public static WorkflowOverview of(WorkflowDefinition definition) {
    Map<String, AgentDefinition> agents =
        definition.agents().stream()
            .collect(Collectors.toMap(AgentDefinition::name, Function.identity(), (a, b) -> a));
    Set<String> providers = new LinkedHashSet<>();
    Set<String> fallbacks = new LinkedHashSet<>();
    Set<String> judges = new LinkedHashSet<>();
    Set<String> phases = new LinkedHashSet<>();
    int children = 0;
    int gates = 0;
    boolean delivers = false;
    boolean readOnly = true;

    for (StageDefinition stage : definition.stages()) {
      if (stage.phase() != null) phases.add(stage.phase());
      List<String> working = new ArrayList<>();
      switch (stage.type()) {
        case AGENT -> working.add(stage.agent());
        case PARALLEL -> {
          children += stage.children().size();
          stage.children().forEach(child -> working.add(child.agent()));
        }
        case VERIFICATION -> {
          if (stage.judge() != null) {
            for (JudgeSpec.Judge judge : stage.judge().agents()) {
              judges.add(judge.agent());
              addProvider(agents.get(judge.agent()), providers, fallbacks);
            }
          }
        }
        case HUMAN_APPROVAL -> gates++;
        case GITHUB_PR -> delivers = true;
        default -> {}
      }
      for (String name : working) {
        AgentDefinition agent = name == null ? null : agents.get(name);
        addProvider(agent, providers, fallbacks);
        if (agent == null || !READ_ONLY_MODES.contains(String.valueOf(agent.permissionMode()))) {
          readOnly = false;
        }
      }
      if (WRITING_TYPES.contains(stage.type())
          || stage.checks().stream()
              .anyMatch(c -> WRITING_CHECKS.contains(String.valueOf(c.name())))) {
        readOnly = false;
      }
    }
    fallbacks.removeAll(providers);

    return new WorkflowOverview(
        definition.stages().size(),
        children,
        gates,
        judges.size(),
        List.copyOf(providers),
        List.copyOf(fallbacks),
        StageDefinition.PHASES.stream().filter(phases::contains).toList(),
        definition.inputs().stream().map(i -> new Input(i.name(), i.required())).toList(),
        delivers,
        readOnly);
  }

  private static void addProvider(
      AgentDefinition agent, Set<String> providers, Set<String> fallbacks) {
    if (agent == null) {
      providers.add(REPOSITORY);
      return;
    }
    providers.add(Objects.requireNonNullElse(agent.provider(), AgentDefinition.CLAUDE_CODE));
    if (agent.fallback() != null) fallbacks.add(agent.fallback().provider());
  }
}
