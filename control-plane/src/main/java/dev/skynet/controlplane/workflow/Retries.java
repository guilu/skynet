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

  /**
   * El hijo de una {@code parallel} que se reintenta: cuántas veces ha corrido y por qué falló; con
   * {@code quota}, espera a que se renueve la cuota de su proveedor (CQ-A).
   */
  record FailedChild(
      String child, int attempts, AgentFailure failure, Duration delay, QuotaWaits.Blocked quota) {

    FailedChild(String child, int attempts, AgentFailure failure, Duration delay) {
      this(child, attempts, failure, delay, null);
    }
  }

  /**
   * Lo que se reintenta tras el último intento, o nada ({@code delay == null}). Con {@code quota},
   * es una espera a que se renueve la cuota (CQ-A): sigue en el worktree en el que se cortó, sin
   * gastar intentos de {@code retry}.
   */
  record Plan(
      Duration delay, AgentFailure failure, List<FailedChild> children, QuotaWaits.Blocked quota) {

    static final Plan NONE = new Plan(null, null, List.of(), null);

    Plan(Duration delay, AgentFailure failure, List<FailedChild> children) {
      this(delay, failure, children, null);
    }

    boolean retries() {
      return delay != null;
    }

    boolean waitsForQuota() {
      return quota != null;
    }
  }

  /**
   * Si un agente que falló por {@code quota} espera a que se renueva la cuota, y cuánto (CQ-A).
   * {@code quotaFailures} y {@code blindFailures} cuentan sus fallos {@code quota} en la fase (o en
   * su hijo), con y sin hora de renovación, incluido este.
   */
  @FunctionalInterface
  interface QuotaPolicy {

    /** Nunca espera: la fase falla como antes de CQ. */
    QuotaPolicy NEVER = (agent, child, quotaFailures, blindFailures, attemptId) -> null;

    /** La espera y lo que se espera, o {@code null} si no espera. */
    QuotaDelay delay(
        AgentRun agent, String child, int quotaFailures, int blindFailures, UUID attemptId);
  }

  /** Cuánto se espera y a qué cuenta. */
  record QuotaDelay(Duration delay, QuotaWaits.Blocked blocked) {}

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

  /**
   * Cuántos intentos van desde {@link #seriesStart}, incluido el último. Los que fallaron por
   * {@code quota} no cuentan (CQ-A): no son culpa del agente.
   */
  int attemptsInSeries() {
    int start = seriesStart();
    return (int)
        attempts.stream()
            .filter(a -> a.getAttempt() >= start)
            .filter(a -> a == latest() || failureOf(a) != AgentFailure.QUOTA)
            .count();
  }

  /** Los agentes de la fase (o de su hijo {@code child}) que fallaron por {@code quota}. */
  private List<AgentRun> quotaFailures(String child) {
    return agents.stream()
        .filter(a -> child == null || child.equals(a.getChildKey()))
        .filter(a -> a.getFailure() == AgentFailure.QUOTA && failed(a))
        .toList();
  }

  /** Lo que espera {@code agent}, que falló por {@code quota}, o {@code null} si no espera. */
  private QuotaDelay quotaDelay(AgentRun agent, String child, QuotaPolicy quota) {
    List<AgentRun> failures = quotaFailures(child);
    int blind = (int) failures.stream().filter(a -> a.getQuotaResetsAt() == null).count();
    return quota.delay(agent, child, failures.size(), blind, latest().getId());
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
            .filter(a -> a.getFailure() != AgentFailure.QUOTA || a == latestByChild().get(child))
            .count();
  }

  /**
   * Si el agente de un hijo de {@code stage} ha fallado de una forma que su {@code retry} cubre.
   */
  boolean childRetries(StageDefinition stage, AgentRun agent) {
    return childRetries(stage, agent, QuotaPolicy.NEVER);
  }

  /** Como {@link #childRetries(StageDefinition, AgentRun)}, o si espera a la cuota (CQ-A). */
  boolean childRetries(StageDefinition stage, AgentRun agent, QuotaPolicy quota) {
    if (agent.getChildKey() == null || !failed(agent)) {
      return false;
    }
    if (failureOf(agent) == AgentFailure.QUOTA
        && quotaDelay(agent, agent.getChildKey(), quota) != null) {
      return true;
    }
    Retry retry = stage.child(agent.getChildKey()).map(ChildDefinition::retry).orElse(null);
    return retry != null && retry.retries(failureOf(agent), runsOf(agent.getChildKey()));
  }

  /** Lo que toca tras el último intento, que ha fallado, sin esperar a la cuota. */
  Plan plan(StageDefinition stage) {
    return plan(stage, QuotaPolicy.NEVER);
  }

  /**
   * Lo que toca tras el último intento, que ha fallado. Un fallo {@code quota} espera a que se
   * renueve la cuota si {@code quota} lo dice (CQ-A), antes y aparte de {@code retry}.
   */
  Plan plan(StageDefinition stage, QuotaPolicy quota) {
    StageRun latest = latest();
    if (stage == null || latest.getStatus() != StageStatus.FAILED) {
      return Plan.NONE;
    }
    if (stage.type() == StageType.PARALLEL) {
      return parallelPlan(stage, quota);
    }
    Retry retry = stage.retry();
    AgentFailure failure = failureOf(latest);
    if (failure == AgentFailure.QUOTA) {
      AgentRun agent =
          agentsOf(latest).stream()
              .filter(a -> a.getFailure() == AgentFailure.QUOTA)
              .findFirst()
              .orElse(null);
      QuotaDelay wait = agent == null ? null : quotaDelay(agent, null, quota);
      if (wait != null) {
        return new Plan(wait.delay(), failure, List.of(), wait.blocked());
      }
    }
    int attempts = attemptsInSeries();
    if (retry == null || !retry.retries(failure, attempts)) {
      return Plan.NONE;
    }
    return new Plan(retry.delayAfter(attempts), failure, List.of());
  }

  private Plan parallelPlan(StageDefinition stage, QuotaPolicy quota) {
    Map<String, AgentRun> latest = latestByChild();
    List<FailedChild> failed = new ArrayList<>();
    Duration delay = null;
    QuotaWaits.Blocked blocked = null;
    for (ChildDefinition child : stage.children()) {
      AgentRun agent = latest.get(child.id());
      if (agent == null || agent.getStatus() == AgentObservableStatus.CANCELLED) {
        return Plan.NONE;
      }
      if (!failed(agent)) {
        continue;
      }
      QuotaDelay quotaWait =
          failureOf(agent) == AgentFailure.QUOTA ? quotaDelay(agent, child.id(), quota) : null;
      if (quotaWait == null && !childRetries(stage, agent)) {
        return Plan.NONE;
      }
      int runs = runsOf(child.id());
      Duration wait = quotaWait != null ? quotaWait.delay() : child.retry().delayAfter(runs);
      failed.add(
          new FailedChild(
              child.id(),
              runs,
              failureOf(agent),
              wait,
              quotaWait == null ? null : quotaWait.blocked()));
      if (delay == null || wait.compareTo(delay) > 0) {
        delay = wait;
        blocked = quotaWait == null ? null : quotaWait.blocked();
      }
    }
    if (failed.isEmpty()) {
      return Plan.NONE;
    }
    return new Plan(delay, failed.getFirst().failure(), List.copyOf(failed), blocked);
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
