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
  - id: tests
    type: command
    command: ./gradlew test  # el comando de tests de tu repositorio
    dependsOn: [implement]
  - id: review
    type: parallel
    dependsOn: [tests]
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
 * y Gemini la seguridad. Ninguno de los dos admite una lista de herramientas, así que sus revisores
 * no la llevan; siguen en solo lectura con `permissionMode: plan`.
 */
const MULTI_PROVIDER_REVIEW = PARALLEL_REVIEW.replace(
  'id: revision-en-paralelo\nname: Revisión en paralelo\n',
  'id: revision-con-codex-y-gemini\nname: Revisión con Codex y Gemini\n',
)
  .replace(/( {2}calidad:\n)/, '$1    provider: codex\n')
  .replace(/( {2}seguridad:\n)/, '$1    provider: gemini\n')
  .replaceAll('    tools: [Read, Grep, Glob]\n    permissionMode: plan', '    permissionMode: plan')

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
    id: 'revision-con-codex-y-gemini',
    name: 'Revisión con Codex y Gemini',
    description:
      'La revisión en paralelo con otros modelos: Codex revisa la calidad y Gemini la seguridad; el resto lo hace Claude Code.',
    yaml: MULTI_PROVIDER_REVIEW,
  },
]
