package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.definition.StageDefinition;
import dev.skynet.controlplane.definition.StageDefinition.ChildDefinition;
import dev.skynet.controlplane.definition.StageDefinition.Retry;
import dev.skynet.controlplane.definition.StageType;
import dev.skynet.protocol.AgentFailure;
import dev.skynet.protocol.AgentObservableStatus;
import dev.skynet.protocol.StageStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Si un intento fallido de una fase se reintenta (W6-A), con los datos de todos sus intentos. Sin
 * efectos: {@link RunSteps} los aplica.
 *
 * <p>Una fase {@code agent} o {@code command} se reintenta si su {@code retry} cubre el fallo y le
 * quedan intentos. En una {@code parallel} cada hijo lleva su {@code retry}: la fase se reintenta
 * si todos los hijos que fallaron pueden, y el intento nuevo repite solo esos.
 *
 * @param attempts intentos de la fase, del primero al último
 * @param agents agentes de todos los intentos, en el orden en que se crearon
 * @param verifications la última verificación de fase de cada agente
 */
record Retries(
    List<StageRun> attempts, List<AgentRun> agents, Map<UUID, VerificationResult> verifications) {

  /** El hijo de una {@code parallel} que se reintenta: cuántas veces ha corrido y por qué falló. */
  record FailedChild(String child, int attempts, AgentFailure failure, Duration delay) {}

  /** Lo que se reintenta tras el último intento, o nada ({@code delay == null}). */
  record Plan(Duration delay, AgentFailure failure, List<FailedChild> children) {

    static final Plan NONE = new Plan(null, null, List.of());

    boolean retries() {
      return delay != null;
    }
  }

  StageRun latest() {
    return attempts.getLast();
  }

  /**
   * El intento desde el que cuentan los reintentos automáticos: el último pedido con «Reintentar
   * fase» (W6-C), que no cuenta para {@code maxAttempts}, o el primero.
   */
  int seriesStart() {
    return attempts.stream()
        .filter(a -> a.getRetriedBy() != null)
        .mapToInt(StageRun::getAttempt)
        .max()
        .orElse(1);
  }

  /** Cuántos intentos van desde {@link #seriesStart}, incluido el último. */
  int attemptsInSeries() {
    return latest().getAttempt() - seriesStart() + 1;
  }

  List<AgentRun> agentsOf(StageRun attempt) {
    return agents.stream().filter(a -> a.getStageRunId().equals(attempt.getId())).toList();
  }

  /**
   * Si el agente ha fallado: terminó mal o terminó bien pero no pasó la verificación de su fase.
   */
  boolean failed(AgentRun agent) {
    return agent.getStatus() == AgentObservableStatus.FAILED
        || agent.getTimedOutAt() != null
        || verificationFailed(agent);
  }

  /** Por qué falló el agente, o {@code null} si no ha fallado o no se sabe. */
  AgentFailure failureOf(AgentRun agent) {
    if (agent.getTimedOutAt() != null) {
      return AgentFailure.TIMEOUT;
    }
    if (agent.getStatus() == AgentObservableStatus.FAILED) {
      return agent.getFailure();
    }
    return verificationFailed(agent) ? AgentFailure.VERIFICATION : null;
  }

  private boolean verificationFailed(AgentRun agent) {
    VerificationResult result = verifications.get(agent.getId());
    return agent.getStatus() == AgentObservableStatus.COMPLETED
        && result != null
        && !result.isLive()
        && !Verifications.PASSED.equals(result.status());
  }

  /**
   * Por qué falló un intento de una fase que no es {@code parallel}: lo que diga su agente fallido.
   * Si llegó a su {@code timeout}, {@link AgentFailure#TIMEOUT}. Sin agente (no llegó a arrancar en
   * el control plane) o con uno cancelado, {@code null}: no se reintenta.
   */
  AgentFailure failureOf(StageRun attempt) {
    if (attempt.getTimedOutAt() != null) {
      return AgentFailure.TIMEOUT;
    }
    List<AgentRun> own = agentsOf(attempt);
    if (own.stream().anyMatch(a -> a.getStatus() == AgentObservableStatus.CANCELLED)) {
      return null;
    }
    return own.stream().filter(this::failed).map(this::failureOf).findFirst().orElse(null);
  }

  /** El último agente de cada hijo de una {@code parallel}, en todos sus intentos. */
  Map<String, AgentRun> latestByChild() {
    Map<String, AgentRun> latest = new LinkedHashMap<>();
    agents.stream()
        .filter(a -> a.getChildKey() != null)
        .forEach(a -> latest.put(a.getChildKey(), a));
    return latest;
  }

  /** Cuántas veces ha corrido el hijo desde {@link #seriesStart}. */
  int runsOf(String child) {
    int start = seriesStart();
    Set<UUID> counted =
        attempts.stream()
            .filter(a -> a.getAttempt() >= start)
            .map(StageRun::getId)
            .collect(Collectors.toSet());
    return (int)
        agents.stream()
            .filter(a -> child.equals(a.getChildKey()) && counted.contains(a.getStageRunId()))
            .count();
  }

  /**
   * Si el agente de un hijo de {@code stage} ha fallado de una forma que su {@code retry} cubre.
   */
  boolean childRetries(StageDefinition stage, AgentRun agent) {
    if (agent.getChildKey() == null || !failed(agent)) {
      return false;
    }
    Retry retry = stage.child(agent.getChildKey()).map(ChildDefinition::retry).orElse(null);
    return retry != null && retry.retries(failureOf(agent), runsOf(agent.getChildKey()));
  }

  /** Lo que toca tras el último intento, que ha fallado. */
  Plan plan(StageDefinition stage) {
    StageRun latest = latest();
    if (stage == null || latest.getStatus() != StageStatus.FAILED) {
      return Plan.NONE;
    }
    if (stage.type() == StageType.PARALLEL) {
      return parallelPlan(stage);
    }
    Retry retry = stage.retry();
    AgentFailure failure = failureOf(latest);
    int attempts = attemptsInSeries();
    if (retry == null || !retry.retries(failure, attempts)) {
      return Plan.NONE;
    }
    return new Plan(retry.delayAfter(attempts), failure, List.of());
  }

  private Plan parallelPlan(StageDefinition stage) {
    Map<String, AgentRun> latest = latestByChild();
    List<FailedChild> failed = new ArrayList<>();
    Duration delay = null;
    for (ChildDefinition child : stage.children()) {
      AgentRun agent = latest.get(child.id());
      if (agent == null || agent.getStatus() == AgentObservableStatus.CANCELLED) {
        return Plan.NONE;
      }
      if (!failed(agent)) {
        continue;
      }
      if (!childRetries(stage, agent)) {
        return Plan.NONE;
      }
      int runs = runsOf(child.id());
      Duration wait = child.retry().delayAfter(runs);
      failed.add(new FailedChild(child.id(), runs, failureOf(agent), wait));
      delay = delay == null || wait.compareTo(delay) > 0 ? wait : delay;
    }
    if (failed.isEmpty()) {
      return Plan.NONE;
    }
    return new Plan(delay, failed.getFirst().failure(), List.copyOf(failed));
  }

  /**
   * Los hijos que ya terminaron bien en algún intento: el siguiente no los repite. Si la fase
   * entera terminó bien y se repite («Reintentar fase» de una fase anterior, W6-C), se repiten
   * todos: solo cuentan los intentos posteriores.
   */
  List<String> succeededChildren() {
    int after =
        attempts.stream()
            .filter(a -> a.getStatus() == StageStatus.SUCCEEDED)
            .mapToInt(StageRun::getAttempt)
            .max()
            .orElse(0);
    Set<UUID> counted =
        attempts.stream()
            .filter(a -> a.getAttempt() > after)
            .map(StageRun::getId)
            .collect(Collectors.toSet());
    Map<String, AgentRun> latest = new LinkedHashMap<>();
    agents.stream()
        .filter(a -> a.getChildKey() != null && counted.contains(a.getStageRunId()))
        .forEach(a -> latest.put(a.getChildKey(), a));
    return latest.values().stream()
        .filter(a -> a.getStatus() == AgentObservableStatus.COMPLETED && !failed(a))
        .map(AgentRun::getChildKey)
        .toList();
  }

  /**
   * El agente de un intento anterior que tomó la última instantánea del worktree {@code
   * workspaceId}: a ella vuelve el siguiente. {@code null} si ninguno llegó a tomarla (no llegaron
   * a tocarlo).
   */
  AgentRun snapshotAgent(UUID workspaceId) {
    AgentRun found = null;
    for (AgentRun agent : agents) {
      if (!agent.getStageRunId().equals(latest().getId())
          && workspaceId.equals(agent.getWorkspaceId())
          && agent.getBaseSnapshot() != null) {
        found = agent;
      }
    }
    return found;
  }

  /** El último agente de los intentos anteriores (del hijo {@code child}, si no es nulo). */
  AgentRun previousAgent(String child) {
    AgentRun previous = null;
    for (AgentRun agent : agents) {
      if (!agent.getStageRunId().equals(latest().getId())
          && (child == null || child.equals(agent.getChildKey()))) {
        previous = agent;
      }
    }
    return previous;
  }
}
