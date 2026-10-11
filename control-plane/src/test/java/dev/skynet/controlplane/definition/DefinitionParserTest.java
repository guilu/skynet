package dev.skynet.controlplane.definition;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.controlplane.definition.DefinitionParser.Expectations;
import dev.skynet.controlplane.definition.DefinitionParser.Parsed;
import dev.skynet.controlplane.definition.Problem.Severity;
import dev.skynet.controlplane.definition.StageDefinition.Dependency;
import java.math.BigDecimal;
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
            "El tipo `human-approval` todavía no se ejecuta: llega en W5",
            "El tipo `parallel` todavía no se ejecuta: llega en W3",
            "El tipo `github-pr` todavía no se ejecuta: llega en S2",
            "`promptTemplate` todavía no se usa: llega en S3",
            "`retry` todavía no se usa: llega en W6",
            "`condition` todavía no se usa: llega en W3");

    WorkflowDefinition definition = parsed.definition();
    assertThat(definition.stage("pull-request").orElseThrow().dependsOn())
        .containsExactly(
            new Dependency("review", false), new Dependency("fix-review-findings", true));
    AgentDefinition developer = definition.agent("developer").orElseThrow();
    assertThat(developer.tools()).containsExactly("Read", "Edit", "Write", "Bash(git:*)");
    assertThat(developer.maxBudgetUsd()).isEqualByComparingTo(new BigDecimal("3"));
    assertThat(definition.stage("implementation").orElseThrow().workspace())
        .isEqualTo(StageDefinition.WorkspaceMode.ISOLATED);
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
            """);
    WorkflowDefinition definition = parsed.definition();
    assertThat(definition.agent("rapido").orElseThrow().model()).isEqualTo("claude-haiku-4-5");
    assertThat(definition.agent("rapido").orElseThrow().provider()).isEqualTo("claude-code");
    assertThat(definition.agent("listo").orElseThrow().model()).isEqualTo("opus");
    assertThat(messages(parsed, Severity.UNSUPPORTED))
        .containsExactly(
            "El proveedor `codex` todavía no se ejecuta: llega con el hito de proveedores. Por"
                + " ahora solo `claude-code`");
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
                + " workItem.externalRef, project.key, project.name e inputs.<nombre>");
    assertThat(errors.get(0).line()).isEqualTo(10);
    assertThat(errors.get(0).column()).isEqualTo(17);
    assertThat(errors.get(1).column()).isEqualTo(35);
    assertThat(messages(parsed, Severity.UNSUPPORTED))
        .containsExactly("`{{stages.plan.summary}}`: usar la salida de otra fase llega en W4");
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
