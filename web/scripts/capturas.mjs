// Capturas de la guía de estilo (docs/ui/capturas): la web compilada contra una API simulada con
// los datos de fixtures/contracts, en claro, en oscuro y en el móvil.
//
//   npm run build && npx vite preview --port 4174 &
//   node scripts/capturas.mjs http://localhost:4174
import { chromium } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const out = path.join(root, 'docs/ui/capturas')
const base = process.argv[2] ?? 'http://localhost:4174'
const fixture = (name) =>
  JSON.parse(fs.readFileSync(path.join(root, 'fixtures/contracts', `${name}.json`), 'utf8'))

const runView = fixture('run-view')
const agent = fixture('agent-run-detail')
const stored = fixture('stored-event')
const event = (sequence, type, payload, at) => ({
  ...stored,
  sequence,
  eventId: `e${sequence}`,
  type,
  payload,
  occurredAt: at,
  recordedAt: at,
})
const EVENTS = [
  event(
    1040,
    'agent.message.received',
    { text: 'Voy a leer calc.py y arreglar la suma.' },
    '2026-10-05T09:00:05Z',
  ),
  event(
    1041,
    'agent.tool.started',
    { toolUseId: 't0', name: 'Read', input: { file_path: '/w/calc.py' } },
    '2026-10-05T09:00:06Z',
  ),
  event(
    1042,
    'agent.tool.completed',
    { toolUseId: 't0', name: 'Read', isError: false, output: 'def add(a, b):\n    return a - b' },
    '2026-10-05T09:00:08Z',
  ),
  event(
    1043,
    'agent.tool.started',
    {
      toolUseId: 't1',
      name: 'Edit',
      input: { file_path: '/w/calc.py', old_string: 'a - b', new_string: 'a + b' },
    },
    '2026-10-05T09:00:10Z',
  ),
  event(
    1044,
    'agent.tool.completed',
    { toolUseId: 't1', name: 'Edit', isError: false, output: 'ok' },
    '2026-10-05T09:00:13Z',
  ),
  event(
    1045,
    'agent.tool.started',
    { toolUseId: 't2', name: 'Bash', input: { command: 'sh check.sh' } },
    '2026-10-05T09:00:15Z',
  ),
  event(
    1046,
    'agent.tool.completed',
    { toolUseId: 't2', name: 'Bash', isError: false, output: 'OK' },
    '2026-10-05T09:00:27Z',
  ),
  event(
    1047,
    'agent.tool.started',
    { toolUseId: 't3', name: 'Bash', input: { command: 'git commit -am "fix add"' } },
    '2026-10-05T09:00:30Z',
  ),
  event(
    1048,
    'agent.tool.completed',
    { toolUseId: 't3', name: 'Bash', isError: false, output: '[main 1a2b] fix add' },
    '2026-10-05T09:00:33Z',
  ),
  event(
    1049,
    'agent.message.received',
    { text: 'Arreglado: add() ya suma y los tests pasan.' },
    '2026-10-05T09:00:40Z',
  ),
]

const DIFF = `diff --git a/calc.py b/calc.py
--- a/calc.py
+++ b/calc.py
@@ -1,5 +1,8 @@
 def add(a, b):
-    return a - b
+    return a + b
+
+def subtract(a, b):
+    return a - b
 
 def mul(a, b):
     return a * b
`
const GIT = JSON.stringify({
  branch: 'skynet/tkm-1/0b6a3c1e',
  baseCommit: 'e423d4a4c8acc52a200b70a76f322933eb3d6679',
  headCommit: 'c3c0c205acb0adea069f8d5ee5d835a24da433b1',
  uncommittedFiles: 0,
  commits: [
    { sha: 'c3c0c205acb0adea', subject: 'fix add', author: 'Skynet', date: '2026-10-05T09:00:33Z' },
  ],
  files: [
    {
      path: 'calc.py',
      status: 'M',
      insertions: 4,
      deletions: 1,
      binary: false,
      diffOffset: 0,
      diffLength: Buffer.byteLength(DIFF),
    },
  ],
})
const artifact = (id, type, name, mediaType, content) => ({
  id,
  agentRunId: agent.id,
  verificationRunId: null,
  type,
  name,
  mediaType,
  size: Buffer.byteLength(content),
  truncated: false,
  metadata: {},
  createdAt: '2026-10-05T09:00:43Z',
})
const CONTENT = { g1: GIT, d1: DIFF }
const ARTIFACTS = [
  artifact('g1', 'GIT_CHANGES', 'git-changes.json', 'application/json', GIT),
  artifact('d1', 'DIFF', 'changes.diff', 'text/x-diff', DIFF),
]

const project = {
  id: 'p1',
  key: 'TKM',
  name: 'TokenMeter',
  description: 'Importador de precios de modelos',
  createdAt: '2026-10-01T10:00:00Z',
}
const workItem = {
  id: 'w1',
  projectId: 'p1',
  key: 'TKM-1',
  title: 'Add model pricing importer',
  description: 'Importar los precios de los modelos desde un CSV y guardarlos por fecha.',
  type: 'FEATURE',
  externalRef: null,
  status: 'OPEN',
  createdAt: '2026-10-05T09:00:00Z',
}
const repository = {
  id: 'r1',
  projectId: 'p1',
  name: 'tokenmeter',
  localPath: '/home/dev/repos/tokenmeter',
  remoteUrl: null,
  defaultBranch: 'main',
  validationCommand: './gradlew test',
  testReportPaths: [],
  agentPolicy: {
    allowedTools: ['Read', 'Glob', 'Grep', 'Edit', 'Bash(git:*)'],
    permissionMode: 'acceptEdits',
    environment: null,
    maxTurns: 30,
    maxBudgetUsd: 2,
    timeoutMinutes: 30,
  },
  agentPolicyCustom: true,
  createdAt: '2026-10-01T10:00:00Z',
}

// La primera ruta que encaja responde; el orden importa.
const ROUTES = [
  [/\/api\/auth\/session/, { username: 'admin' }],
  [/\/api\/settings\/appearance/, { colors: {}, updatedAt: null }],
  [/\/api\/dashboard\/metrics/, fixture('dashboard-metrics')],
  [/\/api\/dashboard$/, fixture('dashboard-summary')],
  [/\/api\/runners$/, fixture('runners')],
  [/\/api\/workflow-runs\?/, fixture('run-page')],
  [/\/api\/workflow-runs\//, runView],
  [/\/api\/agent-runs\/[^/]+\/conversation/, fixture('conversation')],
  [/\/api\/agent-runs\/[^/]+\/artifacts/, ARTIFACTS],
  [/\/api\/agent-runs\/[^/]+\/verifications/, fixture('verifications')],
  [/\/api\/agent-runs\/[^/]+\/cost/, {}],
  [/\/api\/agent-runs\/[^/]+$/, agent],
  [/\/api\/events\/\d+/, EVENTS[2]],
  [/\/api\/events\?/, EVENTS],
  [/\/api\/workflow-definitions/, fixture('workflow-definitions')],
  [/\/api\/workflows$/, fixture('workflows')],
  [/\/api\/workflows\/schema$/, {}],
  [/\/api\/workflows\/revisar-y-corregir$/, fixture('workflow')],
  [/\/api\/workflow-versions\//, fixture('workflow-version')],
  [/\/api\/projects$/, [project]],
  [/\/api\/projects\/p1\/work-items/, [workItem]],
  [/\/api\/projects\/p1\/repositories/, [repository]],
  [/\/api\/projects\/p1$/, project],
  [/\/api\/work-items\/w1\/runs/, [runView]],
  [/\/api\/work-items\/w1$/, workItem],
]

/** EventSource falso que entrega los eventos y se queda abierto, como el de verdad. */
const fakeEventSource = `(${(events) => {
  window.EventSource = class {
    constructor() {
      setTimeout(() => {
        this.onopen?.()
        for (const e of events)
          this.onmessage?.({ data: JSON.stringify(e), lastEventId: String(e.sequence) })
      }, 50)
    }
    close() {}
  }
}})(${JSON.stringify(EVENTS)})`

const run = `/runs/${runView.id}?agent=${agent.id}`
const SHOTS = [
  ['dashboard', '/'],
  ['ejecuciones', '/runs'],
  ['ejecucion', run],
  ['ejecucion-cascada', `${run}&view=waterfall&tab=artifacts`],
  ['proyecto', '/projects/p1?tab=repos'],
  ['trabajo-lanzar', '/work-items/w1', 'Lanzar agente'],
  ['workflows', '/workflows'],
  ['workflow', '/workflows/revisar-y-corregir'],
  // Ajustes con la paleta Frambuesa elegida (sin guardar), para que se vea la vista previa.
  ['ajustes', '/settings', null, 'Frambuesa'],
]
const MODES = [
  ['claro', { width: 1440, height: 900 }, 'light', SHOTS],
  ['oscuro', { width: 1440, height: 900 }, 'dark', SHOTS],
  ['movil', { width: 400, height: 860 }, 'light', [SHOTS[0], SHOTS[2]]],
]

fs.mkdirSync(out, { recursive: true })
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH })
for (const [mode, viewport, colorScheme, shots] of MODES) {
  const context = await browser.newContext({ viewport, colorScheme, reducedMotion: 'reduce' })
  await context.addInitScript(fakeEventSource)
  const page = await context.newPage()
  // Hora fija, poco después de la ejecución de ejemplo, para que las duraciones sean creíbles.
  await page.clock.setFixedTime(new Date('2026-10-05T09:01:35Z'))
  await page.route('**/actuator/health', (route) => route.fulfill({ json: { status: 'UP' } }))
  await page.route('**/api/**', (route) => {
    const url = route.request().url()
    const chunk = url.match(/\/api\/artifacts\/(\w+)\/content\?offset=(\d+)&limit=(\d+)/)
    if (chunk) {
      const bytes = Buffer.from(CONTENT[chunk[1]] ?? '')
      const offset = Number(chunk[2])
      return route.fulfill({
        headers: { 'X-Artifact-Size': String(bytes.length) },
        body: bytes.subarray(offset, offset + Number(chunk[3])),
      })
    }
    const match = ROUTES.find(([re]) => re.test(url))
    return match ? route.fulfill({ json: match[1] }) : route.fulfill({ status: 404, json: {} })
  })
  for (const [name, target, button, radio] of shots) {
    await page.goto(base + target)
    await page.waitForLoadState('networkidle')
    if (button) await page.getByRole('button', { name: button }).click()
    if (radio) await page.getByText(radio, { exact: true }).click()
    await page.waitForTimeout(800)
    await page.screenshot({ path: path.join(out, `${mode}-${name}.png`) })
    console.log(`${mode}-${name}.png`)
  }
  await context.close()
}
await browser.close()
