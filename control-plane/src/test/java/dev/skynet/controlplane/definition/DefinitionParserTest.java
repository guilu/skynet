package dev.skynet.controlplane.definition;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.controlplane.definition.DefinitionParser.Expectations;
import dev.skynet.controlplane.definition.DefinitionParser.Parsed;
import dev.skynet.controlplane.definition.Problem.Severity;
import dev.skynet.controlplane.definition.StageDefinition.Approval;
import dev.skynet.controlplane.definition.StageDefinition.ChildDefinition;
import dev.skynet.controlplane.definition.StageDefinition.Dependency;
import dev.skynet.controlplane.definition.StageDefinition.Retry;
import dev.skynet.controlplane.definition.StageDefinition.Risk;
import dev.skynet.protocol.AgentFailure;
import dev.skynet.protocol.runner.VerificationCheck;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Lectura y validación del YAML de un workflow: cada error con su posición y un mensaje útil. */
class DefinitionParserTest {

  static final String ADHOC =
      """
      id: adhoc
      version: 1
      name: Agente suelto
      inputs:
        prompt:
          type: text
          required: true
      stages:
        - id: agent
          type: agent
          prompt: "{{inputs.prompt}}"
      """;

  /** El ejemplo de §11 de la especificación, con sus agentes declarados. */
  static final String SDD =
      """
      id: sdd-feature
      version: 1

      inputs:
        issue:
          type: string
          required: true

      agents:
        analyst:
          prompt: Analiza {{inputs.issue}}
        architect:
          prompt: Planifica {{workItem.title}}
        developer:
          prompt: Implementa {{workItem.key}}
          tools: [Read, Edit, Write, "Bash(git:*)"]
          limits:
            maxBudgetUsd: 3
        quality-reviewer:
          prompt: Revisa la calidad de {{workItem.key}}
          output:
            type: object
            properties:
              hasBlockingFindings:
                type: boolean
        security-reviewer:
          prompt: Revisa la seguridad de {{workItem.key}}
          output:
            type: object
            properties:
              hasBlockingFindings:
                type: boolean

      stages:
        - id: specification
          type: agent
          agent: analyst
          promptTemplate: prompts/specification.md
          outputSchema: schemas/specification.json
          artifacts:
            - openspec/proposal.md
          retry:
            maxAttempts: 2

        - id: plan
          type: agent
          agent: architect
          dependsOn: [specification]

        - id: approval
          type: human-approval
          dependsOn: [plan]
          action: Aprobar el plan de {{workItem.key}}

        - id: implementation
          type: agent
          agent: developer
          dependsOn: [approval]
          workspace: isolated-worktree

        - id: tests
          type: command
          dependsOn: [implementation]
          command: ./mvnw verify

        - id: review
          type: parallel
          dependsOn: [tests]
          children:
            - agent: quality-reviewer
            - agent: security-reviewer

        - id: fix-review-findings
          type: agent
          agent: developer
          dependsOn: [review]
          condition: review.hasBlockingFindings

        - id: pull-request
          type: github-pr
          dependsOn:
            - review
            - fix-review-findings?

        - id: merge
          type: github-merge
          dependsOn: [pull-request]
      """;

  @Test
  void adhocIsValidAndPublishable() {
    Parsed parsed = parse(ADHOC);
    assertThat(parsed.validation().problems()).isEmpty();
    assertThat(parsed.validation().publishable()).isTrue();
    WorkflowDefinition definition = parsed.definition();
    assertThat(definition.key()).isEqualTo("adhoc");
    assertThat(definition.name()).isEqualTo("Agente suelto");
    assertThat(definition.inputs())
        .containsExactly(new InputDefinition("prompt", "text", true, null, null));
    assertThat(definition.stages()).hasSize(1);
    assertThat(definition.stages().getFirst().type()).isEqualTo(StageType.AGENT);
  }

  @Test
  void theSpecificationExampleIsValidButWaitsForLaterMilestones() {
    Parsed parsed = parse(SDD);
    assertThat(errors(parsed)).isEmpty();
    assertThat(parsed.validation().valid()).isTrue();
    assertThat(parsed.validation().publishable()).isFalse();
    assertThat(messages(parsed, Severity.UNSUPPORTED))
        .contains(
            "El tipo `github-pr` todavía no se ejecuta: llega en S2",
            "`promptTemplate` todavía no se usa: llega en S3");

    WorkflowDefinition definition = parsed.definition();
    assertThat(definition.stage("pull-request").orElseThrow().dependsOn())
        .containsExactly(
            new Dependency("review", false), new Dependency("fix-review-findings", true));
    AgentDefinition developer = definition.agent("developer").orElseThrow();
    assertThat(developer.tools()).containsExactly("Read", "Edit", "Write", "Bash(git:*)");
    assertThat(developer.maxBudgetUsd()).isEqualByComparingTo(new BigDecimal("3"));
    assertThat(definition.stage("implementation").orElseThrow().workspace())
        .isEqualTo(StageDefinition.WorkspaceMode.ISOLATED);
    assertThat(definition.stage("specification").orElseThrow().outputSchemaPath())
        .isEqualTo("schemas/specification.json");
  }

  @Test
  void commandStagesRunAShellCommandInTheWorktreeTheyContinue() {
    Parsed parsed =
        parse(
            """
            id: comandos
            stages:
              - id: implement
                type: agent
                prompt: Implementa
              - id: tests
                type: command
                command: ./gradlew test
                dependsOn: [implement]
              - id: review
                type: agent
                prompt: Revisa
                dependsOn: [implement, tests]
                workspaceFrom: tests
            """);
    assertThat(parsed.validation().problems()).isEmpty();
    assertThat(parsed.validation().publishable()).isTrue();
    StageDefinition tests = parsed.definition().stage("tests").orElseThrow();
    assertThat(tests.type()).isEqualTo(StageType.COMMAND);
    assertThat(tests.command()).isEqualTo("./gradlew test");
    assertThat(tests.agent()).isNull();
  }

  @Test
  void parallelStagesRunTheirChildrenAtOnce() {
    Parsed parsed =
        parse(
            """
            id: revision
            agents:
              calidad:
                prompt: Revisa la calidad
              seguridad:
                prompt: Revisa la seguridad
            stages:
              - id: implement
                type: agent
                prompt: Implementa
              - id: review
                type: parallel
                dependsOn: [implement]
                children:
                  - agent: calidad
                  - agent: seguridad
                  - id: seguridad-estricta
                    agent: seguridad
                    prompt: Revisa la seguridad con lupa
              - id: fix
                type: agent
                prompt: Corrige
                dependsOn: [review]
              - id: harden
                type: command
                command: make harden
                dependsOn: [review, fix]
                workspaceFrom: review.seguridad
            """);
    assertThat(parsed.validation().problems()).isEmpty();
    assertThat(parsed.validation().publishable()).isTrue();
    StageDefinition review = parsed.definition().stage("review").orElseThrow();
    assertThat(review.type()).isEqualTo(StageType.PARALLEL);
    assertThat(review.children())
        .containsExactly(
            new ChildDefinition("calidad", "calidad", null),
            new ChildDefinition("seguridad", "seguridad", null),
            new ChildDefinition("seguridad-estricta", "seguridad", "Revisa la seguridad con lupa"));
  }

  @Test
  void aParallelStageHasChildrenWithAgentsAndNothingElse() {
    Parsed parsed =
        parse(
            """
            id: revision
            inputs:
              rama:
                type: string
            agents:
              calidad:
                prompt: Revisa
              mudo:
                description: sin prompt
            stages:
              - id: sin-hijos
                type: parallel
              - id: con-agente
                type: parallel
                agent: calidad
                children:
                  - agent: calidad
              - id: hijos-mal
                type: parallel
                children:
                  - prompt: hola
                  - agent: calidad
                  - agent: calidad
                  - agent: nadie
                  - agent: mudo
                  - id: Mal
                    agent: calidad
                  - id: otro
                    agent: calidad
                    prompt: "{{inputs.nada}}"
              - id: agente
                type: agent
                prompt: hola
                children:
                  - agent: calidad
              - id: sigue
                type: agent
                prompt: hola
                dependsOn: [hijos-mal, agente]
                workspaceFrom: hijos-mal.seguridad
              - id: sigue-agente
                type: agent
                prompt: hola
                dependsOn: [agente]
                workspaceFrom: agente.calidad
            """);
    assertThat(messages(parsed, Severity.ERROR))
        .containsExactly(
            "A esta fase le falta `children`, los agentes que se ejecutan a la vez, p. ej."
                + " `children: [{agent: calidad}, {agent: seguridad}]`",
            "Una fase `parallel` no tiene agente propio: pon `agent` en cada hijo de `children`",
            "A este hijo le falta `agent`, el agente de `agents` que lo ejecuta",
            "Ya hay otro hijo `calidad` en esta fase: ponle a uno un `id` distinto, p. ej."
                + " `id: calidad-2`",
            "No hay ningún agente `nadie` en `agents`",
            "El hijo `mudo` no tiene prompt: escríbelo en el hijo o en el agente `mudo`",
            "`Mal` no vale como id de hijo: usa minúsculas, números y guiones, empezando por una"
                + " letra",
            "`{{inputs.nada}}` usa un dato que no está en `inputs`",
            "`children` solo vale en fases `type: parallel`; esta es `agent`",
            "La fase `hijos-mal` no tiene ningún hijo `seguridad`",
            "`agente` no es una fase `parallel`: `<fase>.<hijo>` solo vale para continuar el"
                + " worktree de un hijo");
  }

  /** Revisores con salida estructurada, un comando y una corrección que depende de ellos. */
  static final String REVIEW =
      """
      id: revision
      inputs:
        foco:
          type: string
        estricto:
          type: boolean
      agents:
        calidad:
          prompt: Revisa
          output:
            type: object
            properties:
              hasBlockingFindings:
                type: boolean
              score:
                type: number
        seguridad:
          prompt: Revisa
          output:
            type: object
            properties:
              hasBlockingFindings:
                type: boolean
              summary:
                type: string
        plano:
          prompt: Implementa
      stages:
        - id: implement
          type: agent
          agent: plano
        - id: tests
          type: command
          command: make test
          dependsOn: [implement]
        - id: review
          type: parallel
          dependsOn: [tests]
          children:
            - agent: calidad
            - agent: seguridad
        - id: fix
          type: agent
          prompt: Corrige
          dependsOn: [review]
          condition: %s
      """;

  @Test
  void conditionsReadStatusesExitCodesOutputsAndInputs() {
    for (String condition :
        List.of(
            "review.hasBlockingFindings",
            "review.calidad.score >= 7 && !review.seguridad.hasBlockingFindings",
            "tests.exitCode == 0 || implement.status == 'SUCCEEDED'",
            "inputs.estricto && inputs.foco in ['seguridad', 'calidad']",
            "review.seguridad.summary != null")) {
      Parsed parsed = parse(REVIEW.formatted("\"" + condition + "\""));
      assertThat(parsed.validation().problems()).as(condition).isEmpty();
      assertThat(parsed.validation().publishable()).isTrue();
      assertThat(parsed.definition().stage("fix").orElseThrow().condition()).isEqualTo(condition);
    }
    AgentDefinition calidad =
        parse(REVIEW.formatted("review.hasBlockingFindings"))
            .definition()
            .agent("calidad")
            .orElseThrow();
    assertThat(calidad.outputFields())
        .containsExactly(
            java.util.Map.entry("hasBlockingFindings", "boolean"),
            java.util.Map.entry("score", "number"));
  }

  @Test
  void eachWrongConditionSaysWhatAndWhere() {
    assertCondition("review.hasBlockingFindings &&", "La condición termina antes de tiempo", 29);
    assertCondition("nadie.status == 'X'", "`nadie` no es ninguna fase ni `inputs`", 0);
    assertCondition(
        "fix.status == 'X'",
        "`fix` no es una dependencia de `fix`: una condición solo lee fases que han terminado"
            + " antes (ponla en `dependsOn`)",
        0);
    assertCondition(
        "implement.exitCode == 0",
        "Solo las fases `command` tienen `exitCode`; `implement` es `agent`",
        0);
    assertCondition(
        "implement.done",
        "`implement` no devuelve salida estructurada: declara `output` en su agente u"
            + " `outputSchema` en la fase para leer `done`",
        0);
    assertCondition(
        "review.calidad.nada", "El `output` del agente `calidad` no tiene el campo `nada`", 0);
    assertCondition("review.otro.score > 1", "La fase `review` no tiene ningún hijo `otro`", 0);
    assertCondition(
        "review.score > 1",
        "`review.score` junta los hijos y solo vale para campos booleanos (es verdadero si algún"
            + " hijo devuelve true); `score` es number en `calidad`: usa `review.calidad.score`",
        0);
    assertCondition(
        "review.nada",
        "Ningún hijo de `review` devuelve `nada`: declara el campo en el `output` de su agente",
        0);
    assertCondition(
        "inputs.rama == 'main'", "`inputs.rama` usa un dato que no está en `inputs`", 0);
    assertCondition(
        "tests.exitCode", "La condición tiene que ser verdadera o falsa, y esto es un número", 0);
    assertCondition("tests.exitCode == 'cero'", "No se puede comparar un número con un texto", 15);
    assertCondition(
        "tests",
        "`tests` no es un dato que pueda leer una condición: usa `<fase>.status`,"
            + " `<fase>.exitCode`, `<fase>.<campo>` o `inputs.<nombre>`",
        0);
  }

  /** {@code index}: carácter de la condición donde está el error. */
  private static void assertCondition(String condition, String message, int index) {
    Parsed parsed = parse(REVIEW.formatted("\"" + condition + "\""));
    assertThat(errors(parsed))
        .as(condition)
        .singleElement()
        .satisfies(
            p -> {
              assertThat(p.message()).isEqualTo(message);
              assertThat(p.path()).isEqualTo("stages.fix.condition");
              assertThat(p.column()).isEqualTo(index + 17);
            });
  }

  @Test
  void conditionalStagesOnlyEvaluateTheirCondition() {
    Parsed parsed =
        parse(
            """
            id: ramas
            stages:
              - id: tests
                type: command
                command: make test
              - id: si-falla
                type: conditional
                dependsOn: [tests]
                condition: tests.exitCode != 0
              - id: mal
                type: conditional
                prompt: hola
              - id: avisar
                type: agent
                prompt: Avisa
                dependsOn: [si-falla]
            """);
    assertThat(messages(parsed, Severity.ERROR))
        .containsExactly(
            "A esta fase le falta `condition`, p. ej. `condition: review.hasBlockingFindings`",
            "Una fase `conditional` no hace trabajo, solo evalúa su condición: quita `prompt`");
    assertThat(parsed.definition().stage("si-falla").orElseThrow().type())
        .isEqualTo(StageType.CONDITIONAL);
  }

  @Test
  void anOutputIsAnObjectSchemaWithTypedFields() {
    Parsed parsed =
        parse(
            """
            id: salidas
            agents:
              lista:
                prompt: a
                output: [ok]
              sin-tipo:
                prompt: a
                output:
                  properties:
                    ok:
                      type: boolean
              sin-campos:
                prompt: a
                output:
                  type: object
              campos-mal:
                prompt: a
                output:
                  type: object
                  properties:
                    1mal:
                      type: boolean
                    raro:
                      type: fecha
            stages:
              - id: a
                type: agent
                agent: lista
            """);
    assertThat(messages(parsed, Severity.ERROR))
        .containsExactly(
            "`output` debe ser un JSON Schema de un objeto, p. ej. `output: {type: object,"
                + " properties: {hasBlockingFindings: {type: boolean}}}`",
            "La salida estructurada tiene que ser un objeto: pon `type: object` y sus campos en"
                + " `properties`",
            "A `output` le faltan sus campos en `properties`, p. ej. `output: {type: object,"
                + " properties: {hasBlockingFindings: {type: boolean}}}`",
            "`1mal` no vale como campo de `output`: usa letras, números y `_`, empezando por una"
                + " letra",
            "El campo `raro` de `output` necesita `type`: boolean, string, number, integer, array,"
                + " object");
  }

  @Test
  void aCommandStageHasACommandAndNothingElse() {
    Parsed parsed =
        parse(
            """
            id: comandos
            agents:
              dev:
                prompt: hola
            stages:
              - id: sin-comando
                type: command
              - id: con-agente
                type: command
                agent: dev
                prompt: hola
                command: make
              - id: con-variables
                type: command
                command: "git checkout {{inputs.rama}}"
              - id: agente
                type: agent
                agent: dev
                command: make
              - id: varios
                type: agent
                prompt: x
                dependsOn: [con-agente, con-variables]
            """);
    assertThat(messages(parsed, Severity.ERROR))
        .containsExactly(
            "A esta fase le falta `command`, el comando que se ejecuta en el worktree, p. ej."
                + " `command: ./gradlew test`",
            "Una fase `command` ejecuta un comando, no un agente: quita `agent`",
            "Una fase `command` ejecuta un comando, no un agente: quita `prompt`",
            "Los comandos no admiten variables `{{...}}`: un dato de entrada podría colar otro"
                + " comando. Escríbelo tal cual",
            "`command` solo vale en fases `type: command`; esta es `agent`",
            "La fase `varios` depende de varias fases con worktree (con-agente, con-variables):"
                + " indica con `workspaceFrom` cuál continúa, o pon `workspace: isolated-worktree`"
                + " para empezar un worktree nuevo");
  }

  @Test
  void agentsChooseTheirModelAndOnlyClaudeCodeRunsForNow() {
    Parsed parsed =
        parse(
            """
            id: modelos
            agents:
              rapido:
                prompt: hola
                model: claude-haiku-4-5
              listo:
                prompt: hola
                model: opus
                provider: claude-code
              otro:
                prompt: hola
                provider: codex
                tools: [Read]
                limits:
                  maxTurns: 5
                  maxBudgetUsd: 1
              futuro:
                prompt: hola
                provider: opencode
              google:
                prompt: hola
                provider: gemini
                limits:
                  maxTurns: 3
              raro:
                prompt: hola
                provider: gpt
                model: "con espacios"
            stages:
              - id: a
                type: agent
                agent: rapido
              - id: b
                type: agent
                agent: listo
              - id: c
                type: agent
                agent: otro
              - id: d
                type: agent
                agent: raro
              - id: e
                type: agent
                agent: futuro
              - id: f
                type: agent
                agent: google
            """);
    WorkflowDefinition definition = parsed.definition();
    assertThat(definition.agent("rapido").orElseThrow().model()).isEqualTo("claude-haiku-4-5");
    assertThat(definition.agent("rapido").orElseThrow().provider()).isEqualTo("claude-code");
    assertThat(definition.agent("listo").orElseThrow().model()).isEqualTo("opus");
    AgentDefinition codex = definition.agent("otro").orElseThrow();
    assertThat(codex.provider()).isEqualTo("codex");
    assertThat(codex.maxTurns()).isNull();
    assertThat(codex.maxBudgetUsd()).isEqualByComparingTo("1");
    assertThat(messages(parsed, Severity.WARNING))
        .containsExactly(
            "Codex no admite una lista de herramientas: se ignora. Lo que puede hacer lo fija"
                + " `permissionMode` (`plan` es solo lectura)",
            "Codex no admite `maxTurns`: se ignora. Acota el agente con `maxBudgetUsd` o"
                + " `timeoutMinutes`",
            "Gemini CLI no admite `maxTurns`: se ignora. Acota el agente con `maxBudgetUsd` o"
                + " `timeoutMinutes`");
    assertThat(messages(parsed, Severity.UNSUPPORTED))
        .containsExactly(
            "El proveedor `opencode` todavía no se ejecuta. Por ahora solo `claude-code`, `codex`"
                + " y `gemini`");
    assertThat(messages(parsed, Severity.ERROR))
        .containsExactly(
            "Proveedor `gpt` desconocido. Admite: claude-code, codex, gemini, opencode",
            "`con espacios` no vale como modelo: escribe su nombre o su alias, p. ej."
                + " `claude-sonnet-4-5` o `opus`");
  }

  @Test
  void unreadableYamlPointsAtTheProblem() {
    Parsed parsed =
        parse(
            """
            id: roto
            stages:
              - id: a
                type: agent
               prompt: mal sangrado
            """);
    assertThat(parsed.definition()).isNull();
    Problem problem = errors(parsed).getFirst();
    assertThat(problem.message()).startsWith("El YAML no se puede leer");
    assertThat(problem.line()).isEqualTo(5);
  }

  @Test
  void emptyAndMultipleDocumentsAreRejected() {
    assertThat(errors(parse("")).getFirst().message()).startsWith("El documento está vacío");
    assertThat(errors(parse("id: a1\n---\nid: b1\n")).getFirst().message())
        .contains("más de un documento");
  }

  @Test
  void unknownKeysSuggestTheRightOne() {
    Parsed parsed =
        parse(
            """
            id: typo
            stages:
              - id: a
                type: agent
                prompt: hola
              - id: b
                type: agent
                prompt: adiós
                dependOn: [a]
            """);
    Problem problem = errors(parsed).getFirst();
    assertThat(problem.message())
        .isEqualTo("Clave `dependOn` desconocida; ¿quisiste decir `dependsOn`?");
    assertThat(problem.path()).isEqualTo("stages[1].dependOn");
    assertThat(problem.line()).isEqualTo(9);
    assertThat(problem.column()).isEqualTo(5);
  }

  @Test
  void dependenciesMustExistAndNotPointToThemselves() {
    Parsed parsed =
        parse(
            """
            id: deps
            stages:
              - id: plan
                type: agent
                prompt: p
              - id: build
                type: agent
                prompt: b
                dependsOn: [plna, build]
            """);
    assertThat(messages(parsed, Severity.ERROR))
        .containsExactly(
            "No hay ninguna fase `plna`; ¿quisiste decir `plan`?",
            "La fase `build` no puede depender de sí misma");
    assertThat(errors(parsed).getFirst().line()).isEqualTo(9);
  }

  @Test
  void cyclesShowTheirPath() {
    Parsed parsed =
        parse(
            """
            id: ciclo
            stages:
              - id: a
                type: agent
                prompt: a
                dependsOn: [c]
              - id: b
                type: agent
                prompt: b
                dependsOn: [a]
                workspace: isolated-worktree
              - id: c
                type: agent
                prompt: c
                dependsOn: [b]
            """);
    assertThat(messages(parsed, Severity.ERROR))
        .containsExactly("Las dependencias forman un ciclo: a → c → b → a");
  }

  @Test
  void stageIdsAreUnique() {
    Parsed parsed =
        parse(
            """
            id: dup
            stages:
              - id: a
                type: agent
                prompt: uno
              - id: a
                type: agent
                prompt: dos
            """);
    assertThat(messages(parsed, Severity.ERROR))
        .containsExactly("Ya hay otra fase con id `a` (línea 3)");
  }

  @Test
  void agentsMustExistAndSomeoneMustWriteThePrompt() {
    Parsed parsed =
        parse(
            """
            id: agentes
            agents:
              reviewer:
                tools: [Read]
              unused:
                prompt: nadie me usa
            stages:
              - id: a
                type: agent
                agent: reveiwer
              - id: b
                type: agent
                agent: reviewer
                dependsOn: [a]
              - id: c
                type: agent
                dependsOn: [b]
            """);
    assertThat(messages(parsed, Severity.ERROR))
        .containsExactly(
            "No hay ningún agente `reveiwer` en `agents`; ¿quisiste decir `reviewer`?",
            "La fase `b` no tiene prompt: escríbelo en la fase o en el agente `reviewer`",
            "La fase `c` necesita un `prompt` o un `agent` que lo tenga");
    assertThat(messages(parsed, Severity.WARNING))
        .containsExactly("Ninguna fase usa el agente `unused`");
  }

  @Test
  void promptVariablesAreCheckedWhereTheyAre() {
    Parsed parsed =
        parse(
            """
            id: variables
            inputs:
              issue:
                type: string
            stages:
              - id: a
                type: agent
                prompt: |
                  Resuelve {{inputs.issue}} de {{workItem.title}}.
                  Contexto: {{inputs.isue}} y {{workitem.key}}
                  Plan: {{stages.plan.summary}}
            """);
    List<Problem> errors = errors(parsed);
    assertThat(errors)
        .extracting(Problem::message)
        .containsExactly(
            "`{{inputs.isue}}` usa un dato que no está en `inputs`; ¿quisiste decir `issue`?",
            "Variable `{{workitem.key}}` desconocida; ¿quisiste decir `workItem.key`?. Admite:"
                + " workItem.key, workItem.title, workItem.description, workItem.type,"
                + " workItem.externalRef, project.key, project.name, inputs.<nombre> y"
                + " stages.<fase>.output.<campo>",
            "`{{stages.plan.summary}}` no es un dato de otra fase: usa"
                + " `{{stages.<fase>.output.<campo>}}`, `{{stages.<fase>.<hijo>.output.<campo>}}`,"
                + " `{{stages.<fase>.status}}` o `{{stages.<fase>.exitCode}}`");
    assertThat(errors.get(0).line()).isEqualTo(10);
    assertThat(errors.get(0).column()).isEqualTo(17);
    assertThat(errors.get(1).column()).isEqualTo(35);
    assertThat(errors.get(2).line()).isEqualTo(11);
    assertThat(errors.get(2).column()).isEqualTo(13);
    assertThat(messages(parsed, Severity.UNSUPPORTED)).isEmpty();
  }

  /** Una especificación con su salida en la fase, un plan que la lee y una revisión en paralelo. */
  private static final String SPEC_PLAN =
      """
      id: especificar
      agents:
        analista:
          prompt: Especifica {{workItem.title}}
          output:
            type: object
            properties:
              borrador:
                type: string
        planificador:
          prompt: "Planifica a partir de: {{stages.spec.output.summary}}"
        revisor:
          prompt: Revisa
          output:
            type: object
            properties:
              hasBlockingFindings:
                type: boolean
      stages:
        - id: spec
          type: agent
          agent: analista
          outputSchema:
            type: object
            properties:
              summary:
                type: string
              risks:
                type: array
        - id: externo
          type: agent
          prompt: Usa el schema del repositorio
          outputSchema: schemas/externo.json
        - id: tests
          type: command
          command: make test
          dependsOn: [spec]
        - id: review
          type: parallel
          dependsOn: [tests]
          children:
            - agent: revisor
        - id: plan
          type: agent
          agent: planificador
          dependsOn: [review, externo]
          workspaceFrom: review
        - id: resumen
          type: agent
          dependsOn: [plan]
          condition: spec.summary != null && externo.loQueSea == 'x'
          prompt: |
            Riesgos: {{stages.spec.output.risks}}
            Tests: {{stages.tests.status}} ({{stages.tests.exitCode}})
            Bloqueos: {{stages.review.revisor.output.hasBlockingFindings}}
            Externo: {{stages.externo.output.cualquiera}}
      """;

  @Test
  void aStageOutputSchemaWinsOverItsAgentsAndPromptsReadOtherStages() {
    Parsed parsed = parse(SPEC_PLAN);
    assertThat(parsed.validation().problems()).isEmpty();
    assertThat(parsed.validation().publishable()).isTrue();
    StageDefinition spec = parsed.definition().stage("spec").orElseThrow();
    assertThat(AgentDefinition.fieldsOf(spec.outputSchema()))
        .containsExactly(
            java.util.Map.entry("summary", "string"), java.util.Map.entry("risks", "array"));
    assertThat(spec.outputSchemaJson()).contains("\"summary\"").doesNotContain("borrador");
    StageDefinition externo = parsed.definition().stage("externo").orElseThrow();
    assertThat(externo.outputSchemaPath()).isEqualTo("schemas/externo.json");
    assertThat(externo.outputSchemaJson()).isEqualTo("{\"$ref\":\"schemas/externo.json\"}");

    // Lo que lee cada prompt de otras fases, también el de su agente.
    WorkflowDefinition definition = parsed.definition();
    assertThat(definition.reads("plan")).containsExactly("spec.output.summary");
    assertThat(definition.reads("resumen"))
        .containsExactly(
            "spec.output.risks",
            "tests.status",
            "tests.exitCode",
            "review.revisor.output.hasBlockingFindings",
            "externo.output.cualquiera");
    assertThat(definition.reads("spec")).isEmpty();
    assertThat(definition.reads("no-existe")).isEmpty();
  }

  @Test
  void eachWrongStageVariableSaysWhy() {
    assertStageVariable(
        "{{stages.spec.output.borrador}}",
        "`{{stages.spec.output.borrador}}`: El `outputSchema` de la fase `spec` no tiene el campo"
            + " `borrador`");
    assertStageVariable(
        "{{stages.resumen.status}}",
        "`{{stages.resumen.status}}`: `resumen` no es una dependencia de `resumen`: un prompt"
            + " solo lee fases que han terminado antes (ponla en `dependsOn`)");
    assertStageVariable(
        "{{stages.nadie.output.x}}", "`{{stages.nadie.output.x}}`: no hay ninguna fase `nadie`");
    assertStageVariable(
        "{{stages.review.output.hasBlockingFindings}}",
        "`{{stages.review.output.hasBlockingFindings}}`: `review` es `parallel` y cada hijo"
            + " devuelve su salida; usa `{{stages.review.<hijo>.output.hasBlockingFindings}}`");
    assertStageVariable(
        "{{stages.review.otro.output.x}}",
        "`{{stages.review.otro.output.x}}`: La fase `review` no tiene ningún hijo `otro`");
    assertStageVariable(
        "{{stages.plan.output.x}}",
        "`{{stages.plan.output.x}}`: `plan` no devuelve salida estructurada: declara `output` en"
            + " su agente u `outputSchema` en la fase para leer `x`");
    assertStageVariable(
        "{{stages.spec.exitCode}}",
        "`{{stages.spec.exitCode}}`: Solo las fases `command` tienen `exitCode`; `spec` es"
            + " `agent`");
  }

  private static void assertStageVariable(String variable, String message) {
    Parsed parsed =
        parse(SPEC_PLAN.replace("{{stages.tests.status}}", "{{stages.tests.status}} " + variable));
    assertThat(errors(parsed))
        .as(variable)
        .singleElement()
        .satisfies(
            p -> {
              assertThat(p.message()).isEqualTo(message);
              assertThat(p.path()).isEqualTo("stages.resumen.prompt");
            });
  }

  @Test
  void anAgentPromptIsCheckedFromEveryStageThatUsesIt() {
    Parsed parsed =
        parse(
            SPEC_PLAN
                + """
                  - id: suelto
                    type: agent
                    agent: planificador
                """);
    assertThat(errors(parsed))
        .singleElement()
        .satisfies(
            p -> {
              assertThat(p.path()).isEqualTo("agents.planificador.prompt");
              assertThat(p.message())
                  .isEqualTo(
                      "`{{stages.spec.output.summary}}`: `spec` no es una dependencia de"
                          + " `suelto`: un prompt solo lee fases que han terminado antes (ponla"
                          + " en `dependsOn`)");
            });
  }

  @Test
  void anOutputSchemaIsInlineOrARepositoryPathOnAgentStages() {
    Parsed parsed =
        parse(
            """
            id: schemas
            stages:
              - id: fuera
                type: agent
                prompt: a
                outputSchema: ../secretos.json
              - id: absoluta
                type: agent
                prompt: a
                outputSchema: /etc/schema.json
              - id: sin-campos
                type: agent
                prompt: a
                outputSchema:
                  type: object
              - id: lista
                type: agent
                prompt: a
                outputSchema: [ok]
              - id: comando
                type: command
                command: make
                outputSchema: schemas/x.json
              - id: paralela
                type: parallel
                outputSchema: schemas/x.json
                children:
                  - agent: r
            agents:
              r:
                prompt: r
            """);
    assertThat(messages(parsed, Severity.ERROR))
        .containsExactly(
            "`outputSchema: ../secretos.json` tiene que ser una ruta dentro del repositorio, p. ej."
                + " `schemas/spec.json`",
            "`outputSchema: /etc/schema.json` tiene que ser una ruta dentro del repositorio, p. ej."
                + " `schemas/spec.json`",
            "A `outputSchema` le faltan sus campos en `properties`, p. ej. `outputSchema: {type:"
                + " object, properties: {hasBlockingFindings: {type: boolean}}}`",
            "`outputSchema` debe ser un JSON Schema de un objeto o la ruta de un fichero del"
                + " repositorio, p. ej. `outputSchema: {type: object, properties:"
                + " {hasBlockingFindings: {type: boolean}}}`",
            "`outputSchema` solo vale en fases `agent`",
            "`outputSchema` solo vale en fases `agent`: en una `parallel`, declara `output` en el"
                + " agente de cada hijo");
  }

  @Test
  void aStageDeclaresWhatSkynetChecksInItsWorktree() {
    Parsed parsed =
        parse(
            """
            id: verificada
            stages:
              - id: spec
                type: agent
                prompt: Especifica
                artifacts: [docs/spec.md, "openspec/**/*.md"]
                checks: [diff, commit, scope, {command: npm run lint}, tests]
                scope: ["docs/**", openspec/**]
              - id: verify
                type: verification
                dependsOn: [spec]
                checks: [tests]
            """);
    assertThat(parsed.validation().problems()).isEmpty();
    StageDefinition spec = parsed.definition().stage("spec").orElseThrow();
    assertThat(spec.checks())
        .containsExactly(
            VerificationCheck.withPaths("artifacts", List.of("docs/spec.md", "openspec/**/*.md")),
            VerificationCheck.named("diff"),
            VerificationCheck.named("commit"),
            VerificationCheck.withPaths("scope", List.of("docs/**", "openspec/**")),
            VerificationCheck.command("npm run lint"),
            VerificationCheck.named("tests"));
    assertThat(spec.verified()).isTrue();
    assertThat(spec.runsTests()).isTrue();
    StageDefinition verify = parsed.definition().stage("verify").orElseThrow();
    assertThat(verify.checks()).containsExactly(VerificationCheck.named("tests"));
    assertThat(parsed.definition().stage("verify").orElseThrow().type().executable()).isTrue();
  }

  @Test
  void eachWrongCheckSaysWhy() {
    Parsed parsed =
        parse(
            """
            id: checks-mal
            inputs:
              cmd:
                type: string
            stages:
              - id: a
                type: agent
                prompt: a
                artifacts: [../fuera.md]
                checks: [test, scope, {run: make}, {command: "make {{inputs.cmd}}"}]
              - id: b
                type: agent
                prompt: b
                dependsOn: [a]
                checks: tests
                artifacts: docs/spec.md
              - id: c
                type: conditional
                dependsOn: [b]
                condition: b.status == "SUCCEEDED"
                checks: [diff]
            """);
    assertThat(messages(parsed, Severity.ERROR))
        .containsExactly(
            "`../fuera.md` tiene que ser una ruta dentro del repositorio, p. ej. `docs/**`",
            "Check `test` desconocido; ¿quisiste decir `tests`?. Admite: tests, diff, commit,"
                + " scope, command: <comando>",
            "El check `scope` necesita la lista `scope` con los ficheros que se pueden cambiar,"
                + " p. ej. `scope: [src/**, docs/**]`",
            "Un check con datos solo puede ser `command: <comando>`. Admite: tests, diff, commit,"
                + " scope, command: <comando>",
            "Los comandos no admiten variables `{{...}}`: un dato de entrada podría colar otro"
                + " comando. Escríbelo tal cual",
            "`checks` debe ser una lista, p. ej. `checks: [tests, diff]`",
            "`artifacts` debe ser una lista de rutas, p. ej. `artifacts: [openspec/proposal.md]`",
            "`checks` solo vale en fases `agent`, `command`, `parallel` o `verification`: comprueba"
                + " el worktree que dejan");
  }

  @Test
  void scopeWithoutItsCheckAndRepeatedChecksAreWarnings() {
    Parsed parsed =
        parse(
            """
            id: avisos
            stages:
              - id: a
                type: agent
                prompt: a
                checks: [diff, diff]
                scope: [src/**]
            """);
    assertThat(errors(parsed)).isEmpty();
    assertThat(messages(parsed, Severity.WARNING))
        .containsExactly(
            "`diff` está repetido", "`scope` no se comprueba: añade `scope` a `checks`");
  }

  @Test
  void aVerificationStageOnlyChecksTheWorktreeItInherits() {
    Parsed parsed =
        parse(
            """
            id: nodos
            stages:
              - id: build
                type: command
                command: make
              - id: vacia
                type: verification
                dependsOn: [build]
              - id: hace-cosas
                type: verification
                dependsOn: [build]
                prompt: Revisa
                checks: [tests]
              - id: aislada
                type: verification
                dependsOn: [build]
                workspace: isolated-worktree
                checks: [tests]
              - id: huerfana
                type: verification
                checks: [diff]
            """);
    assertThat(messages(parsed, Severity.ERROR))
        .containsExactly(
            "A esta fase le falta qué comprobar: `artifacts` (ficheros que tienen que existir) o"
                + " `checks`, p. ej. `checks: [tests, diff]`",
            "Una fase `verification` no ejecuta nada, solo comprueba el worktree que hereda:"
                + " quita `prompt`",
            "Una fase `verification` comprueba el worktree que hereda: quita `workspace:"
                + " isolated-worktree`",
            "Una fase `verification` comprueba el worktree de la fase de la que depende: ponla"
                + " en `dependsOn` detrás de una fase `agent`, `command` o `parallel`");
  }

  @Test
  void anApprovalGateNamesItsActionRiskAndTheStageThatRevises() {
    Parsed parsed =
        parse(
            """
            id: puertas
            stages:
              - id: spec
                type: agent
                prompt: Especifica
                outputSchema:
                  type: object
                  properties:
                    summary:
                      type: string
              - id: plan
                type: agent
                prompt: Planifica
                dependsOn: [spec]
              - id: aprobar-plan
                type: human-approval
                dependsOn: [plan]
                action: "Aprobar el plan de {{workItem.key}}: {{stages.spec.output.summary}}"
                risk: high
              - id: aprobar-spec
                type: human-approval
                dependsOn: [plan]
                action: Aprobar la especificación
                revise: spec
              - id: implement
                type: agent
                prompt: Implementa
                dependsOn: [aprobar-plan, aprobar-spec]
                workspaceFrom: aprobar-plan
            """);
    assertThat(parsed.validation().problems()).isEmpty();
    assertThat(parsed.validation().publishable()).isTrue();
    WorkflowDefinition definition = parsed.definition();
    StageDefinition gate = definition.stage("aprobar-plan").orElseThrow();
    assertThat(gate.approval())
        .isEqualTo(
            new Approval(
                "Aprobar el plan de {{workItem.key}}: {{stages.spec.output.summary}}",
                Risk.HIGH,
                null));
    assertThat(definition.revise("aprobar-plan")).isEqualTo("plan");
    assertThat(definition.revise("aprobar-spec")).isEqualTo("spec");
    assertThat(definition.stage("aprobar-spec").orElseThrow().approval().risk())
        .isEqualTo(Risk.MEDIUM);
    assertThat(definition.reads("aprobar-plan")).containsExactly("spec.output.summary");
    assertThat(definition.dependents("plan")).containsExactly("aprobar-plan", "aprobar-spec");
  }

  @Test
  void anApprovalGateOnlyWaitsForADecision() {
    Parsed parsed =
        parse(
            """
            id: puertas-mal
            stages:
              - id: build
                type: command
                command: make
              - id: plan
                type: agent
                prompt: Planifica
              - id: sin-accion
                type: human-approval
                dependsOn: [plan]
              - id: hace-cosas
                type: human-approval
                dependsOn: [plan]
                action: Aprobar
                prompt: Revisa
                risk: altisimo
              - id: corrige-comando
                type: human-approval
                dependsOn: [build]
                action: Aprobar
                revise: build
              - id: corrige-despues
                type: human-approval
                dependsOn: [plan]
                action: "Aprobar {{stages.plan.output.nada}}"
                revise: tarde
              - id: tarde
                type: agent
                prompt: Después
                action: Aprobar
            """);
    assertThat(messages(parsed, Severity.ERROR))
        .containsExactlyInAnyOrder(
            "A esta fase le falta `action`, lo que se pide aprobar, p. ej. `action: Aprobar el"
                + " plan de {{workItem.key}}`",
            "Una fase `human-approval` no ejecuta nada, espera a que alguien decida: quita"
                + " `prompt`",
            "`risk: altisimo` no se conoce. Admite: low, medium, high",
            "`build` es una fase `command`: al pedir cambios, el comentario se manda al agente de"
                + " una fase `agent`",
            "`tarde` no va antes de esta puerta: `revise` tiene que ser una fase de la que"
                + " depende",
            "`action` solo vale en fases `type: human-approval`; esta es `agent`",
            messages(parsed, Severity.ERROR).stream()
                .filter(m -> m.startsWith("`{{stages.plan.output.nada}}`"))
                .findFirst()
                .orElse("falta el error de la variable"));
  }

  @Test
  void aStageWithSeveralAgentDependenciesSaysWhichWorktreeItContinues() {
    String base =
        """
        id: fan-in
        stages:
          - id: back
            type: agent
            prompt: b
          - id: front
            type: agent
            prompt: f
          - id: join
            type: agent
            prompt: j
            dependsOn: [back, front]
        """;
    assertThat(messages(parse(base), Severity.ERROR))
        .containsExactly(
            "La fase `join` depende de varias fases con worktree (back, front): indica con"
                + " `workspaceFrom` cuál continúa, o pon `workspace: isolated-worktree` para"
                + " empezar un worktree nuevo");
    assertThat(errors(parse(base + "    workspaceFrom: back\n"))).isEmpty();
    assertThat(errors(parse(base + "    workspace: isolated-worktree\n"))).isEmpty();
    assertThat(messages(parse(base + "    workspaceFrom: other\n"), Severity.ERROR))
        .containsExactly(
            "`workspaceFrom` tiene que ser una de las fases de `dependsOn`: back, front");
  }

  @Test
  void agentSettingsAreValidated() {
    Parsed parsed =
        parse(
            """
            id: ajustes
            agents:
              dev:
                prompt: hola
                tools: [Read, "rm -rf", Read]
                permissionMode: dontask
                limits:
                  maxTurns: 0
                  maxBudgetUsd: muchos
            stages:
              - id: a
                type: agent
                agent: dev
            """);
    assertThat(messages(parsed, Severity.ERROR))
        .containsExactly(
            "`rm -rf` no parece una herramienta de Claude Code, como `Edit` o `Bash(git:*)`",
            "Modo de permisos `dontask` no admitido; ¿quisiste decir `dontAsk`?. Admite: dontAsk,"
                + " acceptEdits, default, plan",
            "`agents.dev.limits.maxTurns` debe estar entre 1 y 1000",
            "`agents.dev.limits.maxBudgetUsd` debe ser un número");
    assertThat(messages(parsed, Severity.WARNING)).containsExactly("`Read` está repetida");
  }

  @Test
  void theKeyCannotChangeAndTheVersionIsSkynets() {
    Parsed parsed = DefinitionParser.parse(ADHOC, new Expectations("otro", 3));
    assertThat(messages(parsed, Severity.ERROR))
        .containsExactly("El id de un workflow no se puede cambiar: este es `otro`");
    assertThat(messages(parsed, Severity.WARNING))
        .containsExactly("La versión la pone Skynet: esta se guardará como la 3, no la 1");
    assertThat(parsed.key()).isEqualTo("otro");
  }

  @Test
  void withVersionRewritesOnlyTheVersion() {
    String next = DefinitionParser.withVersion(ADHOC, 2);
    assertThat(next).isEqualTo(ADHOC.replace("version: 1", "version: 2"));
    String noVersion = "id: a1\nstages:\n  - id: a\n    type: agent\n    prompt: x\n";
    assertThat(DefinitionParser.withVersion(noVersion, 2)).isEqualTo(noVersion);
  }

  @Test
  void unknownTypesAndWrongShapesAreErrors() {
    Parsed parsed =
        parse(
            """
            id: formas
            stages:
              - id: a
                type: agnet
                prompt: x
                dependsOn: a
              - nada
            """);
    assertThat(messages(parsed, Severity.ERROR))
        .containsExactly(
            "Tipo de fase `agnet` desconocido; ¿quisiste decir `agent`?. Admite: agent, command,"
                + " parallel, conditional, verification, human-approval, github-pr, github-check,"
                + " github-merge, merge, deploy",
            "`dependsOn` debe ser una lista, p. ej. `dependsOn: [plan]`",
            "`stages[1]` debe ser un objeto con claves (`clave: valor`)");
  }

  @Test
  void theExampleInTheDocsIsPublishable() throws java.io.IOException {
    String docs = java.nio.file.Files.readString(java.nio.file.Path.of("../docs/workflows.md"));
    int start = docs.indexOf("```yaml\n") + "```yaml\n".length();
    String yaml = docs.substring(start, docs.indexOf("```", start));
    Parsed parsed = parse(yaml);
    assertThat(parsed.validation().problems()).isEmpty();
    assertThat(parsed.definition().inputs().getFirst().defaultValue()).isEqualTo("seguridad");
  }

  @Test
  void retryRepeatsAgentAndCommandStagesAndEachParallelChild() {
    Parsed parsed =
        parse(
            """
            id: reintentos
            agents:
              calidad:
                prompt: Revisa
              seguridad:
                prompt: Revisa la seguridad
            stages:
              - id: implement
                type: agent
                prompt: Implementa
                retry:
                  maxAttempts: 3
              - id: tests
                type: command
                command: make test
                dependsOn: [implement]
                retry: {maxAttempts: 2, on: [timeout, error], delay: 2m}
              - id: review
                type: parallel
                dependsOn: [tests]
                children:
                  - agent: calidad
                    retry: {maxAttempts: 2, delay: 1h}
                  - agent: seguridad
            """);
    assertThat(parsed.validation().problems()).isEmpty();
    assertThat(parsed.validation().publishable()).isTrue();
    WorkflowDefinition definition = parsed.definition();
    assertThat(definition.stage("implement").orElseThrow().retry())
        .isEqualTo(new Retry(3, Retry.DEFAULT_ON, Duration.ofSeconds(30)));
    assertThat(definition.stage("tests").orElseThrow().retry())
        .isEqualTo(
            new Retry(2, List.of(AgentFailure.TIMEOUT, AgentFailure.ERROR), Duration.ofMinutes(2)));
    StageDefinition review = definition.stage("review").orElseThrow();
    assertThat(review.retry()).isNull();
    assertThat(review.child("calidad").orElseThrow().retry().delay())
        .isEqualTo(Duration.ofHours(1));
    assertThat(review.child("seguridad").orElseThrow().retry()).isNull();

    // La espera se dobla en cada intento, hasta una hora.
    Retry retry = definition.stage("implement").orElseThrow().retry();
    assertThat(retry.delayAfter(1)).isEqualTo(Duration.ofSeconds(30));
    assertThat(retry.delayAfter(2)).isEqualTo(Duration.ofSeconds(60));
    assertThat(new Retry(5, Retry.DEFAULT_ON, Duration.ofMinutes(40)).delayAfter(3))
        .isEqualTo(Duration.ofHours(1));
    assertThat(retry.retries(AgentFailure.LOST, 2)).isTrue();
    assertThat(retry.retries(AgentFailure.LOST, 3)).isFalse();
    assertThat(retry.retries(AgentFailure.TIMEOUT, 1)).isFalse();
    assertThat(retry.retries(null, 1)).isFalse();
  }

  @Test
  void timeoutLimitsEachAttemptOfAStageAndNotAConditionalOne() {
    Parsed parsed =
        parse(
            """
            id: limites
            stages:
              - id: implement
                type: agent
                prompt: Implementa
                timeout: 45m
              - id: aprobar
                type: human-approval
                dependsOn: [implement]
                action: Aprobar
                timeout: 72h
              - id: tests
                type: command
                command: make test
                dependsOn: [aprobar]
                timeout: 30s
            """);
    assertThat(parsed.validation().problems()).isEmpty();
    assertThat(parsed.validation().publishable()).isTrue();
    WorkflowDefinition definition = parsed.definition();
    assertThat(definition.stage("implement").orElseThrow().timeout())
        .isEqualTo(Duration.ofMinutes(45));
    assertThat(definition.stage("aprobar").orElseThrow().timeoutSeconds()).isEqualTo(259_200);
    assertThat(definition.stage("tests").orElseThrow().timeout()).isEqualTo(Duration.ofSeconds(30));

    Parsed wrong =
        parse(
            """
            id: limites
            stages:
              - id: implement
                type: agent
                prompt: Implementa
                timeout: 200h
              - id: decide
                type: conditional
                dependsOn: [implement]
                condition: implement.status == "SUCCEEDED"
                timeout: 1m
              - id: tests
                type: command
                command: make test
                dependsOn: [implement]
                timeout: media hora
            """);
    assertThat(messages(wrong, Severity.ERROR))
        .containsExactlyInAnyOrder(
            "`stages.implement.timeout` no puede pasar de 168 h",
            "`timeout` no vale en una fase `conditional`: solo evalúa su condición",
            "`media hora` no es una duración: usa segundos, minutos u horas, p. ej. `30s`, `2m`");
  }

  @Test
  void retryOnlyFitsStagesThatDoWorkAndSaysWhatIsWrong() {
    Parsed parsed =
        parse(
            """
            id: reintentos-mal
            agents:
              calidad:
                prompt: Revisa
            stages:
              - id: implement
                type: agent
                prompt: Implementa
                retry: {on: [error]}
              - id: tests
                type: command
                command: make test
                dependsOn: [implement]
                retry: {maxAttempts: 9, on: [errror], delay: 5 minutos}
              - id: review
                type: parallel
                dependsOn: [tests]
                retry: {maxAttempts: 2}
                children:
                  - agent: calidad
                    retry: {maxAttempts: 2, delay: 2h}
              - id: aprobar
                type: human-approval
                dependsOn: [review]
                action: Aprobar
                retry: {maxAttempts: 2}
            """);
    assertThat(messages(parsed, Severity.ERROR))
        .containsExactlyInAnyOrder(
            "A `retry` le falta `maxAttempts`, los intentos en total, p. ej. `maxAttempts: 3`",
            "`stages.tests.retry.maxAttempts` debe estar entre 1 y 5",
            "Fallo `errror` desconocido; ¿quisiste decir `error`?. Admite: lost, start, error,"
                + " timeout, budget, verification",
            "`5 minutos` no es una duración: usa segundos, minutos u horas, p. ej. `30s`, `2m`",
            "En una fase `parallel`, pon `retry` en cada hijo de `children`: se reintenta solo el"
                + " hijo que falla",
            "`stages.review.children[0].retry.delay` no puede pasar de 1 h",
            "`retry` solo vale en fases `agent` y `command`; esta es `human-approval`");
  }

  private static Parsed parse(String yaml) {
    return DefinitionParser.parse(yaml, Expectations.NONE);
  }

  private static List<Problem> errors(Parsed parsed) {
    return parsed.validation().problems().stream()
        .filter(p -> p.severity() == Severity.ERROR)
        .toList();
  }

  private static List<String> messages(Parsed parsed, Severity severity) {
    return parsed.validation().problems().stream()
        .filter(p -> p.severity() == severity)
        .map(Problem::message)
        .toList();
  }
}
