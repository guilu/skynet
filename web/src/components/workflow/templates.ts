/** Un punto de partida para un workflow nuevo. */
export interface WorkflowTemplate {
  id: string
  name: string
  description: string
  yaml: string
}

/** Dos fases: la segunda continúa el worktree de la primera. */
const BASIC = `id: mi-workflow
name: Mi workflow
description: Qué hace este workflow.

inputs:
  foco:
    type: string
    required: false
    description: En qué fijarse.

agents:
  reviewer:
    prompt: |
      Revisa {{workItem.key}} ({{workItem.title}}) poniendo el foco en {{inputs.foco}}.
    tools: [Read, Grep, Glob]

stages:
  - id: review
    type: agent
    agent: reviewer
  - id: fix
    type: agent
    prompt: Corrige lo que encontró la revisión.
    dependsOn: [review]
`

/**
 * Implementar → tests → revisión de calidad y de seguridad a la vez → corregir solo si algún
 * revisor encuentra algo que bloquee → tests finales.
 */
const PARALLEL_REVIEW = `id: revision-en-paralelo
name: Revisión en paralelo
description: Implementa, pasa los tests, revisa calidad y seguridad a la vez y corrige solo si algún revisor encuentra algo que bloquee.

agents:
  developer:
    prompt: |
      Implementa {{workItem.key}}: {{workItem.title}}.

      {{workItem.description}}
  calidad:
    prompt: |
      Revisa la calidad del cambio de {{workItem.key}} ({{workItem.title}}): legibilidad, tests y casos límite.
      Pon hasBlockingFindings a true solo si hay algo que impida fusionarlo.
    tools: [Read, Grep, Glob]
    permissionMode: plan
    output:
      type: object
      properties:
        hasBlockingFindings: {type: boolean}
        summary: {type: string}
  seguridad:
    prompt: |
      Revisa la seguridad del cambio de {{workItem.key}} ({{workItem.title}}): entradas, secretos y permisos.
      Pon hasBlockingFindings a true solo si hay algo que impida fusionarlo.
    tools: [Read, Grep, Glob]
    permissionMode: plan
    output:
      type: object
      properties:
        hasBlockingFindings: {type: boolean}
        summary: {type: string}

stages:
  - id: implement
    type: agent
    agent: developer
    phase: implement
  - id: tests
    type: command
    command: ./gradlew test  # el comando de tests de tu repositorio
    dependsOn: [implement]
    phase: test
  - id: review
    type: parallel
    dependsOn: [tests]
    phase: review
    children:
      - agent: calidad
      - agent: seguridad
  - id: fix
    type: agent
    agent: developer
    prompt: Corrige lo que bloquea según la revisión de {{workItem.key}} y vuelve a pasar los tests.
    dependsOn: [review]
    condition: review.hasBlockingFindings
  - id: tests-finales
    type: command
    command: ./gradlew test  # el mismo comando de tests
    dependsOn: [review, "fix?"]
    workspaceFrom: review
`

/**
 * La misma revisión en paralelo con una segunda opinión de otros modelos: Codex revisa la calidad
 * y Antigravity la seguridad. Ninguno de los dos admite una lista de herramientas, así que sus revisores
 * no la llevan; siguen en solo lectura con `permissionMode: plan`.
 */
const MULTI_PROVIDER_REVIEW = PARALLEL_REVIEW.replace(
  'id: revision-en-paralelo\nname: Revisión en paralelo\n',
  'id: revision-con-codex-y-antigravity\nname: Revisión con Codex y Antigravity\n',
)
  .replace(/( {2}calidad:\n)/, '$1    provider: codex\n')
  .replace(/( {2}seguridad:\n)/, '$1    provider: antigravity\n')
  .replaceAll('    tools: [Read, Grep, Glob]\n    permissionMode: plan', '    permissionMode: plan')

/**
 * El tramo de especificación de §20: la especificación deja su propuesta en el repositorio y la
 * resume, el plan la recibe en su prompt, una persona aprueba el plan (o pide cambios, que corrige
 * el propio plan en su sesión) y la implementación solo termina bien si pasan los tests y hay
 * cambios. Lo comprueba Skynet en el worktree, no lo que diga el agente.
 */
const SPEC_PLAN_IMPLEMENT = `id: especificar-planificar-implementar
name: Especificar, planificar e implementar
description: Especifica el trabajo en openspec/proposal.md, planifica a partir del resumen, espera a que apruebes el plan e implementa con los tests en verde.

agents:
  analista:
    prompt: |
      Especifica {{workItem.key}}: {{workItem.title}}.

      {{workItem.description}}

      Escribe la propuesta en openspec/proposal.md (qué cambia, por qué y cómo se comprueba) y confírmala con un commit.
      Devuelve un resumen de una o dos frases y los riesgos que veas.
    permissionMode: acceptEdits
  desarrollador:
    prompt: Implementa {{workItem.key}} siguiendo openspec/tasks.md y confirma los cambios.
    permissionMode: acceptEdits

stages:
  - id: spec
    type: agent
    agent: analista
    phase: spec
    outputSchema:
      type: object
      properties:
        summary: {type: string}
        risks: {type: array}
    artifacts: [openspec/proposal.md]
  - id: plan
    type: agent
    agent: desarrollador
    dependsOn: [spec]
    phase: plan
    prompt: |
      Planifica {{workItem.key}} a partir de su propuesta (openspec/proposal.md):
      {{stages.spec.output.summary}}

      Riesgos: {{stages.spec.output.risks}}

      Escribe las tareas en openspec/tasks.md y confírmalas con un commit. No implementes nada todavía.
  - id: aprobar-plan
    type: human-approval
    dependsOn: [plan]
    action: "Aprobar el plan de {{workItem.key}} (openspec/tasks.md): {{stages.spec.output.summary}}"
    risk: medium
    revise: plan
  - id: implement
    type: agent
    agent: desarrollador
    dependsOn: [aprobar-plan]
    phase: implement
    checks: [tests, diff]
`

/** Lo mínimo para empezar desde el grafo (W8-C): una fase de agente sin prompt. */
const BLANK = `id: mi-workflow
name: Mi workflow

stages:
  - id: agent
    type: agent
    prompt: ""
`

export const TEMPLATES: WorkflowTemplate[] = [
  {
    id: 'basico',
    name: 'Dos fases',
    description: 'Una revisión y una corrección que continúa su worktree.',
    yaml: BASIC,
  },
  {
    id: 'revision-en-paralelo',
    name: 'Revisión en paralelo',
    description:
      'Implementar, tests, revisión de calidad y de seguridad a la vez, corregir solo si hay hallazgos que bloquean y tests finales.',
    yaml: PARALLEL_REVIEW,
  },
  {
    id: 'revision-con-codex-y-antigravity',
    name: 'Revisión con Codex y Antigravity',
    description:
      'La revisión en paralelo con otros modelos: Codex revisa la calidad y Antigravity la seguridad; el resto lo hace Claude Code.',
    yaml: MULTI_PROVIDER_REVIEW,
  },
  {
    id: 'especificar-planificar-implementar',
    name: 'Especificar, planificar e implementar',
    description:
      'Especifica en openspec/proposal.md, planifica a partir del resumen, aprueba el plan e implementa; Skynet comprueba que exista la propuesta y que pasen los tests.',
    yaml: SPEC_PLAN_IMPLEMENT,
  },
  {
    id: 'desde-cero',
    name: 'Desde cero',
    description:
      'Una sola fase de agente, sin prompt: para montar el workflow en la pestaña Grafo, sin escribir YAML.',
    yaml: BLANK,
  },
]
