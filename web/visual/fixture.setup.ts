import { expect, test as setup, type Page } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { TEMPLATES } from '../src/components/workflow/templates.ts'
import { login, postApi, publishWorkflowViaApi } from '../e2e/helpers.ts'
import { authFile, fixtureFile, type Fixture } from './paths.ts'

/**
 * Datos de demostración de la galería (VS-A): genéricos, sin secretos ni rutas personales, y
 * sembrados por la API en el entorno aislado de scripts/screenshots.sh. Deja los ids que usan los
 * preparadores en fixture.json y la sesión en auth.json (los dos fuera del repositorio).
 *
 * Los agentes son los simulados de las E2E: fake-claude corrige `add()` en calc.py y la
 * verificación del repositorio (check.sh) pasa; los revisores de Codex y Antigravity devuelven lo
 * que deja la fase anterior en `.revision.json`, y el agente llama a la memoria con el guion de
 * `.memoria.json`.
 */

const template = (id: string) => TEMPLATES.find((t) => t.id === id)!.yaml

/** «Revisión con Codex y Antigravity» con comandos que valen en el repositorio de juguete. */
const MULTI_PROVIDER = template('revision-con-codex-y-antigravity')
  .replace(
    /command: \.\/gradlew test .*\n/,
    `command: printf '${JSON.stringify({ hasBlockingFindings: true, summary: 'Falta un caso con texto vacío' })}' > .revision.json\n`,
  )
  .replace(/command: \.\/gradlew test .*\n/, 'command: sh check.sh\n')

/** «Especificar, planificar e implementar»: se para en la puerta `aprobar-plan`. */
const WITH_APPROVAL = template('especificar-planificar-implementar')
  .replace(
    '  - id: spec\n    type: agent',
    [
      '  - id: preparar',
      '    type: command',
      `    command: mkdir -p openspec && echo '# Propuesta' > openspec/proposal.md && printf '${JSON.stringify({ summary: 'Separar el lector del contador', risks: ['cambia la API interna'] })}' > .revision.json && git add -A && git -c user.email=demo@example.invalid -c user.name=demo commit -qm preparar`,
      '  - id: spec',
      '    type: agent',
      '    dependsOn: [preparar]',
    ].join('\n'),
  )
  .replace('checks: [tests, diff]', 'checks: [tests]')

const MEMORY_CALLS = [
  {
    tool: 'mem_save',
    arguments: {
      kind: 'DECISION',
      title: 'Normalizar el texto a NFC antes de contar',
      topic_key: 'texto/normalizacion',
      what: 'Las palabras con tilde cuentan igual escritas con o sin carácter combinado.',
    },
  },
  {
    tool: 'mem_save',
    arguments: {
      kind: 'PATTERN',
      title: 'Un test por regla de conteo',
      topic_key: 'tests/conteo',
      what: 'Cada regla (acentos, guiones, apóstrofos) tiene su propio caso en PalabrasTest.',
    },
  },
]

const REPORT = `# Informe del repositorio

| Módulo | Qué hace |
| --- | --- |
| \`contador\` | Cuenta palabras y frases |
| \`calc\` | Suma los totales de varios ficheros |

## Riesgos

- Los textos con tildes combinadas cuentan doble.
- No hay límite de tamaño al leer ficheros.
`

const DOCUMENT = `id: documentar-repositorio
name: Documentar el repositorio
description: Genera un informe del repositorio y guarda las decisiones en la memoria del proyecto.

stages:
  - id: informe
    type: command
    command: |
      mkdir -p docs
      cat > docs/informe.md <<'MD'
${REPORT.replace(/^/gm, '      ')}
      MD
      cat > .memoria.json <<'JSON'
      ${JSON.stringify(MEMORY_CALLS)}
      JSON
      git add docs && git -c user.email=demo@example.invalid -c user.name=demo commit -qm informe
    artifacts: [docs/informe.md]
  - id: documentar
    type: agent
    prompt: Revisa el informe del repositorio y guarda en la memoria las decisiones importantes.
    dependsOn: [informe]
`

const QUICK_TESTS = `id: pruebas-rapidas
name: Pruebas rápidas
description: Pasa los tests del repositorio sin cambiar nada.

stages:
  - id: tests
    type: command
    command: sh check.sh
`

const CONTINUOUS = `id: analisis-continuo
name: Análisis continuo
description: Analiza el repositorio y se queda vigilando los cambios.

stages:
  - id: analizar
    type: agent
    prompt: Analiza cómo se cuentan las palabras y propón mejoras.
  - id: vigilar
    type: command
    command: sleep 3600
    dependsOn: [analizar]
`

interface RunView {
  status: string
  stages: { stageKey: string; status: string; agents: { id: string }[] }[]
}

async function runView(page: Page, runId: string): Promise<RunView> {
  return (await page.request.get(`/api/workflow-runs/${runId}`)).json()
}

/** Espera a que la ejecución cumpla `done` y devuelve su vista. */
async function waitRun(page: Page, runId: string, done: (v: RunView) => boolean) {
  let view: RunView | undefined
  await expect
    .poll(
      async () => {
        view = await runView(page, runId)
        return done(view)
      },
      { timeout: 240_000, intervals: [2_000] },
    )
    .toBe(true)
  return view!
}

const agentOf = (view: RunView, stage: string) =>
  view.stages.find((s) => s.stageKey === stage)!.agents[0].id

setup('datos de demostración', async ({ page }) => {
  setup.setTimeout(600_000)
  await page.goto('/')
  await login(page)
  await expect(page.getByRole('navigation', { name: 'Navegación principal' })).toBeVisible()

  const project = await postApi(page, '/api/projects', {
    key: 'DEMO',
    name: 'Analizador de texto',
    description: 'Herramienta de ejemplo que cuenta palabras y frases y suma totales.',
  })
  const repo = await postApi(page, `/api/projects/${project.id}/repositories`, {
    name: 'analizador-texto',
    localPath: process.env.E2E_REPO_PATH,
    validationCommand: 'sh check.sh',
  })
  const web = await postApi(page, '/api/projects', {
    key: 'WEB',
    name: 'Panel de lectura',
    description: 'Interfaz web para consultar los informes.',
  })
  const empty = await postApi(page, '/api/projects', {
    key: 'PROTO',
    name: 'Prototipos',
    description: 'Ideas sin empezar.',
  })
  const item = (projectId: string, title: string, type: string, description: string) =>
    postApi(page, `/api/projects/${projectId}/work-items`, { title, type, description })
  const accents = await item(
    project.id,
    'Contar palabras con tildes y signos',
    'FEATURE',
    'Las palabras con tilde o diéresis deben contar una sola vez.',
  )
  const totals = await item(
    project.id,
    'La suma de totales resta en vez de sumar',
    'BUG',
    'Al combinar dos ficheros, el total sale menor que cada parte.',
  )
  const docs = await item(
    project.id,
    'Documentar el formato de entrada',
    'FEATURE',
    'Explicar qué codificaciones y saltos de línea se aceptan.',
  )
  const reader = await item(
    project.id,
    'Separar el lector del contador',
    'REFACTOR',
    'El lector de ficheros y el contador viven en el mismo módulo.',
  )
  const security = await item(
    project.id,
    'Revisar la lectura de ficheros grandes',
    'SECURITY_REVIEW',
    'Comprobar que un fichero enorme no agota la memoria.',
  )
  await item(project.id, 'Informe de frecuencias por capítulo', 'FEATURE', 'Nueva salida en CSV.')
  await item(web.id, 'Tabla de informes con filtros', 'FEATURE', 'Filtrar por fecha y proyecto.')

  await postApi(page, `/api/projects/${project.id}/memory`, {
    repositoryId: repo.id,
    kind: 'CONSTRAINT',
    title: 'Entrada siempre en UTF-8',
    what: 'Los ficheros de entrada se leen como UTF-8; si fallan, se avisa y se omiten.',
  })
  await postApi(page, `/api/projects/${project.id}/memory`, {
    kind: 'CONFIG',
    title: 'Informes en español y Markdown',
    what: 'Los informes del proyecto se escriben en español y en Markdown.',
  })

  const multi = await publishWorkflowViaApi(page, MULTI_PROVIDER)
  const document = await publishWorkflowViaApi(page, DOCUMENT)
  const quick = await publishWorkflowViaApi(page, QUICK_TESTS)
  const approval = await publishWorkflowViaApi(page, WITH_APPROVAL)
  const continuous = await publishWorkflowViaApi(page, CONTINUOUS)
  // Un borrador v2 para el editor visual.
  const draft = await postApi(page, '/api/workflows/revision-con-codex-y-antigravity/draft', {})

  const launch = (workItemId: string, body: Record<string, unknown>) =>
    postApi(page, `/api/work-items/${workItemId}/runs`, { repositoryId: repo.id, ...body })
  const completed = await launch(totals.id, { definitionId: multi })
  const documented = await launch(docs.id, { definitionId: document })
  const single = await launch(accents.id, {
    prompt: 'Corrige add() en calc.py para que sume los totales y añade un test.',
  })
  const failed = await launch(security.id, { definitionId: quick })
  const gated = await launch(reader.id, { definitionId: approval })

  const completedView = await waitRun(page, completed.id, (v) => v.status === 'SUCCEEDED')
  const documentedView = await waitRun(page, documented.id, (v) => v.status === 'SUCCEEDED')
  await waitRun(page, single.id, (v) => v.status === 'SUCCEEDED')
  await waitRun(page, failed.id, (v) => v.status === 'FAILED')
  let approvalId = ''
  await expect
    .poll(
      async () => {
        const list: { id: string; workflowRunId: string }[] = await (
          await page.request.get('/api/approvals?status=PENDING')
        ).json()
        approvalId = list.find((a) => a.workflowRunId === gated.id)?.id ?? ''
        return approvalId
      },
      { timeout: 240_000, intervals: [2_000] },
    )
    .not.toBe('')

  // La última: se queda en curso (la fase `vigilar` duerme) mientras se captura.
  const active = await launch(accents.id, { definitionId: continuous })
  await waitRun(
    page,
    active.id,
    (v) => v.stages.find((s) => s.stageKey === 'vigilar')?.status === 'RUNNING',
  )

  const fixture: Fixture = {
    projectId: project.id,
    emptyProjectId: empty.id,
    workItemId: totals.id,
    workflowKey: 'revision-con-codex-y-antigravity',
    publishedVersionId: multi,
    draftVersionId: draft.id,
    approvalId,
    runs: {
      completed: completed.id,
      documented: documented.id,
      failed: failed.id,
      active: active.id,
    },
    agents: {
      completed: agentOf(completedView, 'implement'),
      report: agentOf(documentedView, 'informe'),
      memory: agentOf(documentedView, 'documentar'),
    },
    // El reloj del navegador se para un minuto después de sembrar: así «hace 3 min» y las
    // duraciones salen iguales en todas las capturas.
    frozenAt: Date.now() + 60_000,
  }
  await writeFile(fixtureFile, JSON.stringify(fixture, null, 2))
  await page.context().storageState({ path: authFile })
})
