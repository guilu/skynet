import {
  isMap,
  isScalar,
  isSeq,
  parseDocument,
  Scalar,
  type Document,
  type YAMLMap,
  type YAMLSeq,
} from 'yaml'

/**
 * Cambios del YAML de un workflow hechos desde el grafo (W8). Cada operación recibe el texto y
 * devuelve el texto nuevo: edita el documento YAML (no lo regenera), así que se conservan los
 * comentarios, el orden de las claves y el estilo de cada lista. Las líneas que no cambian salen
 * tal cual estaban. Si la operación no se puede hacer, lanza `EditError` con el motivo y el texto
 * no cambia.
 */

/** Los tipos de fase que el motor ejecuta, los de la paleta. */
export const STAGE_TYPES = [
  'agent',
  'command',
  'parallel',
  'conditional',
  'verification',
  'human-approval',
] as const
export type StageType = (typeof STAGE_TYPES)[number]

/** El `id` de una fase (y de un hijo): como en el control plane. */
const STAGE_ID = /^[a-z][a-z0-9-]{0,63}$/

/** Un cambio que no se puede hacer, con el motivo para quien edita. */
export class EditError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'EditError'
  }
}

/** Una dependencia tal como está en `dependsOn`: `plan?` es opcional. */
export interface Dependency {
  stage: string
  optional: boolean
}

/** Lo que se puede cambiar de una fase con `setStageField`. */
export type StageField =
  | 'name'
  | 'type'
  | 'agent'
  | 'prompt'
  | 'command'
  | 'condition'
  | 'workspace'
  | 'workspaceFrom'
  | 'phase'
  | 'action'
  | 'risk'
  | 'revise'
  | 'timeout'

/** Lo que se puede cambiar de un hijo de una `parallel`. */
export type ChildField = 'agent' | 'prompt'

type Value = string | number | boolean | null

// ---- Lectura ------------------------------------------------------------------------------------

function parse(text: string): Document {
  const doc = parseDocument(text)
  if (doc.errors.length > 0)
    throw new EditError('El YAML tiene errores de sintaxis: corrígelos en la pestaña YAML')
  return doc
}

function stagesOf(doc: Document): YAMLSeq {
  const stages = doc.get('stages', true)
  if (!isSeq(stages)) throw new EditError('El workflow no tiene una lista `stages`')
  return stages
}

function stageMaps(doc: Document): YAMLMap[] {
  return stagesOf(doc).items.filter((item): item is YAMLMap => isMap(item))
}

function idOf(map: YAMLMap): string | null {
  const id = map.get('id')
  return typeof id === 'string' ? id : null
}

function findStage(doc: Document, id: string): YAMLMap {
  const stage = stageMaps(doc).find((s) => idOf(s) === id)
  if (!stage) throw new EditError(`No hay ninguna fase \`${id}\``)
  return stage
}

function parseDependency(value: unknown): Dependency | null {
  if (typeof value !== 'string' || value.trim() === '') return null
  const text = value.trim()
  return text.endsWith('?')
    ? { stage: text.slice(0, -1), optional: true }
    : { stage: text, optional: false }
}

/** Las dependencias de una fase; `dependsOn: plan` (sin lista) también vale. */
function dependenciesOf(stage: YAMLMap): Dependency[] {
  const value = stage.get('dependsOn', true)
  if (isSeq(value))
    return value.items
      .map((item) => parseDependency(isScalar(item) ? item.value : item))
      .filter((d): d is Dependency => d != null)
  if (isScalar(value)) {
    const dep = parseDependency(value.value)
    return dep ? [dep] : []
  }
  return []
}

function childrenOf(stage: YAMLMap): YAMLMap[] {
  const children = stage.get('children', true)
  return isSeq(children) ? children.items.filter((c): c is YAMLMap => isMap(c)) : []
}

/** El `id` de un hijo: el suyo o, si no lo dice, el de su agente. */
function childIdOf(child: YAMLMap): string | null {
  const id = child.get('id')
  if (typeof id === 'string') return id
  const agent = child.get('agent')
  return typeof agent === 'string' ? agent : null
}

/** Las fases del texto con sus dependencias, para el grafo y los ciclos. Sin lanzar errores. */
export function readStages(text: string): { id: string; dependsOn: Dependency[] }[] {
  try {
    return stageMaps(parse(text)).flatMap((s) => {
      const id = idOf(s)
      return id ? [{ id, dependsOn: dependenciesOf(s) }] : []
    })
  } catch {
    return []
  }
}

/** Las fases que dependen de `id` (directamente), p. ej. para avisar antes de borrarla. */
export function dependentsOf(text: string, id: string): string[] {
  return readStages(text)
    .filter((s) => s.dependsOn.some((d) => d.stage === id))
    .map((s) => s.id)
}

/**
 * El camino de dependencias de `from` a `to` siguiendo `dependsOn` (`from` depende de … de `to`),
 * o null si no hay ninguno.
 */
export function dependencyPath(
  stages: { id: string; dependsOn: Dependency[] }[],
  from: string,
  to: string,
): string[] | null {
  const byId = new Map(stages.map((s) => [s.id, s]))
  const seen = new Set<string>()
  const walk = (id: string): string[] | null => {
    if (id === to) return [id]
    if (seen.has(id)) return null
    seen.add(id)
    for (const dep of byId.get(id)?.dependsOn ?? []) {
      const rest = walk(dep.stage)
      if (rest) return [id, ...rest]
    }
    return null
  }
  return walk(from)
}

// ---- Escritura ----------------------------------------------------------------------------------

function render(original: string, doc: Document): string {
  return keepUntouchedLines(original, doc.toString({ lineWidth: 0, flowCollectionPadding: false }))
}

/** Para comparar líneas sin lo que `yaml` normaliza al escribir: los espacios antes de un comentario. */
function normalized(line: string): string {
  return line.replace(/\s+#/g, ' #').trimEnd()
}

/** El comentario al final de una línea (`  # algo`), con sus espacios delante. */
const TRAILING_COMMENT = /(\s+)(#[^'"]*)$/

/**
 * Las líneas del texto nuevo que ya estaban (salvo lo que `yaml` normaliza al escribir) se
 * devuelven tal cual estaban, para que un cambio no toque el formato del resto del fichero. En
 * las que cambian, un comentario que ya estaba conserva los espacios que llevaba delante.
 */
export function keepUntouchedLines(original: string, generated: string): string {
  const before = original.split('\n')
  const after = generated.split('\n')
  const a = before.map(normalized)
  const b = after.map(normalized)
  // La subsecuencia común más larga, por líneas: los workflows tienen cientos de líneas como mucho.
  const lcs: number[][] = Array.from({ length: a.length + 1 }, () =>
    new Array<number>(b.length + 1).fill(0),
  )
  for (let i = a.length - 1; i >= 0; i--)
    for (let j = b.length - 1; j >= 0; j--)
      lcs[i][j] = a[i] === b[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1])
  const result = [...after]
  const removed: string[] = []
  const restoreComment = (j: number) => {
    const comment = TRAILING_COMMENT.exec(after[j])
    if (!comment) return
    const old = removed
      .map((line) => TRAILING_COMMENT.exec(line))
      .find((m) => m?.[2] === comment[2])
    if (old) result[j] = after[j].slice(0, comment.index) + old[1] + comment[2]
  }
  let i = 0
  let j = 0
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      result[j] = before[i]
      removed.length = 0
      i++
      j++
    } else if (lcs[i + 1][j] >= lcs[i][j + 1]) removed.push(before[i++])
    else restoreComment(j++)
  }
  while (i < a.length) removed.push(before[i++])
  while (j < b.length) restoreComment(j++)
  return result.join('\n')
}

function checkId(id: string, what: string) {
  if (!STAGE_ID.test(id))
    throw new EditError(
      `El id de ${what} tiene que empezar por una minúscula y llevar solo minúsculas, números y guiones`,
    )
}

/** Un texto como escalar: en bloque (`|`) si tiene varias líneas. */
function scalarFor(value: Exclude<Value, null>): Scalar {
  const scalar = new Scalar(value)
  if (typeof value === 'string' && value.includes('\n')) scalar.type = Scalar.BLOCK_LITERAL
  return scalar
}

/** Cambia el valor de una clave conservando su estilo; null o '' la quitan. */
function setValue(map: YAMLMap, key: string, value: Value) {
  if (value == null || value === '') {
    map.delete(key)
    return
  }
  const current = map.get(key, true)
  if (isScalar(current)) {
    const multiline = typeof value === 'string' && value.includes('\n')
    current.value = value
    // Un texto de varias líneas no cabe en una comilla simple ni en un escalar plano.
    if (multiline && current.type !== Scalar.BLOCK_LITERAL && current.type !== Scalar.BLOCK_FOLDED)
      current.type = Scalar.BLOCK_LITERAL
    if (
      !multiline &&
      (current.type === Scalar.BLOCK_LITERAL || current.type === Scalar.BLOCK_FOLDED)
    )
      current.type = Scalar.PLAIN
    return
  }
  map.set(key, scalarFor(value))
}

function dependencyScalar(dep: Dependency, flow: boolean): Scalar {
  const scalar = new Scalar(dep.optional ? `${dep.stage}?` : dep.stage)
  // `[plan?]` lo lee mal más de un parser: entre corchetes, la opcional va entre comillas.
  if (dep.optional && flow) scalar.type = Scalar.QUOTE_DOUBLE
  return scalar
}

/**
 * Deja `dependsOn` con esas dependencias: cambia solo los elementos que cambian y conserva el
 * estilo de la lista; si no queda ninguna, quita la clave; si no había, la crea como `[a, b]`.
 */
function writeDependencies(doc: Document, stage: YAMLMap, deps: Dependency[]) {
  if (deps.length === 0) {
    stage.delete('dependsOn')
    return
  }
  const current = stage.get('dependsOn', true)
  if (isSeq(current)) {
    const kept = current.items.filter((item) => {
      const dep = parseDependency(isScalar(item) ? item.value : item)
      return dep != null && deps.some((d) => d.stage === dep.stage)
    })
    current.items = deps.map((dep) => {
      const existing = kept.find((item) => {
        const old = parseDependency(isScalar(item) ? item.value : item)
        return old?.stage === dep.stage && old.optional === dep.optional
      })
      return existing ?? dependencyScalar(dep, current.flow === true)
    })
    return
  }
  const seq = doc.createNode([]) as YAMLSeq
  seq.flow = true
  seq.items = deps.map((dep) => dependencyScalar(dep, true))
  stage.set('dependsOn', seq)
}

/** Las claves que se escriben, en este orden, al crear una fase de cada tipo. */
function newStageFields(type: StageType): [string, unknown][] {
  switch (type) {
    case 'agent':
      return [['prompt', '']]
    case 'command':
      return [['command', '']]
    case 'parallel':
      return [['children', []]]
    case 'conditional':
      return [['condition', '']]
    case 'verification':
      return [['checks', ['tests']]]
    case 'human-approval':
      return [['action', '']]
  }
}

function freeId(taken: Set<string>, base: string): string {
  if (!taken.has(base)) return base
  for (let n = 2; ; n++) if (!taken.has(`${base}-${n}`)) return `${base}-${n}`
}

/**
 * Añade una fase del tipo dado con un `id` libre y lo mínimo de su tipo (lo que falte lo marca la
 * validación). Con `after`, la fase nueva va justo detrás de esa y depende de ella.
 */
export function addStage(
  text: string,
  type: StageType,
  options: { after?: string | null } = {},
): { text: string; id: string } {
  const doc = parse(text)
  const stages = stagesOf(doc)
  const taken = new Set(
    stageMaps(doc)
      .map(idOf)
      .filter((id): id is string => id != null),
  )
  const id = freeId(taken, type === 'human-approval' ? 'aprobacion' : type)
  const map = doc.createNode({ id, type }) as YAMLMap
  for (const [key, value] of newStageFields(type)) {
    const node = doc.createNode(value)
    if (isSeq(node)) node.flow = true
    if (isScalar(node) && node.value === '') node.type = Scalar.QUOTE_DOUBLE
    map.set(key, node)
  }
  let index = stages.items.length
  if (options.after) {
    const after = findStage(doc, options.after)
    index = stages.items.indexOf(after) + 1
    writeDependencies(doc, map, [{ stage: options.after, optional: false }])
  }
  stages.items.splice(index, 0, map)
  return { text: render(text, doc), id }
}

/**
 * Borra una fase y sus apariciones en las demás: en `dependsOn`, en `workspaceFrom` (también
 * `fase.hijo`) y en `revise`. Las condiciones y los prompts que lean su salida los marca la
 * validación.
 */
export function removeStage(text: string, id: string): string {
  const doc = parse(text)
  const stages = stagesOf(doc)
  const stage = findStage(doc, id)
  if (stageMaps(doc).length === 1)
    throw new EditError('Es la única fase: un workflow necesita al menos una')
  stages.items.splice(stages.items.indexOf(stage), 1)
  for (const other of stageMaps(doc)) {
    const deps = dependenciesOf(other)
    if (deps.some((d) => d.stage === id))
      writeDependencies(
        doc,
        other,
        deps.filter((d) => d.stage !== id),
      )
    const from = other.get('workspaceFrom')
    if (typeof from === 'string' && (from === id || from.startsWith(`${id}.`)))
      other.delete('workspaceFrom')
    if (other.get('revise') === id) other.delete('revise')
  }
  return render(text, doc)
}

/** Cambia `<id>.` por `<nuevo>.` donde empieza un dato (no dentro de otro nombre). */
function renameReferences(value: string, from: string, to: string, prefix: string): string {
  const escaped = from.replace(/[.*+?^${}()|[\]\\-]/g, '\\$&')
  return value.replace(
    new RegExp(`(^|[^\\w.-])(${prefix})${escaped}(?=\\.)`, 'g'),
    (_m, before: string, start: string) => `${before}${start}${to}`,
  )
}

/** Aplica `change` a un escalar de texto conservando su estilo. */
function rewriteScalar(map: YAMLMap, key: string, change: (value: string) => string) {
  const node = map.get(key, true)
  if (isScalar(node) && typeof node.value === 'string') {
    const next = change(node.value)
    if (next !== node.value) node.value = next
  }
}

/**
 * Cambia el `id` de una fase y lo que la nombra: `dependsOn` (con su `?`), `workspaceFrom`
 * (también `fase.hijo`), `revise`, las condiciones (`fase.campo`) y los datos de fases en los
 * prompts y acciones (`{{stages.fase.…}}`).
 */
export function renameStage(text: string, from: string, to: string): string {
  if (from === to) return text
  checkId(to, 'la fase')
  const doc = parse(text)
  const stage = findStage(doc, from)
  if (stageMaps(doc).some((s) => idOf(s) === to)) throw new EditError(`Ya hay una fase \`${to}\``)
  setValue(stage, 'id', to)
  const inPrompt = (value: string) => renameReferences(value, from, to, '\\{\\{\\s*stages\\.')
  for (const other of stageMaps(doc)) {
    const deps = dependenciesOf(other)
    if (deps.some((d) => d.stage === from)) {
      const value = other.get('dependsOn', true)
      if (isSeq(value))
        for (const item of value.items)
          if (isScalar(item) && typeof item.value === 'string') {
            const dep = parseDependency(item.value)
            if (dep?.stage === from) item.value = dep.optional ? `${to}?` : to
          }
      if (isScalar(value) && typeof value.value === 'string') {
        const dep = parseDependency(value.value)
        if (dep?.stage === from) value.value = dep.optional ? `${to}?` : to
      }
    }
    rewriteScalar(other, 'workspaceFrom', (v) =>
      v === from ? to : v.startsWith(`${from}.`) ? `${to}${v.slice(from.length)}` : v,
    )
    rewriteScalar(other, 'revise', (v) => (v === from ? to : v))
    rewriteScalar(other, 'condition', (v) => renameReferences(v, from, to, ''))
    rewriteScalar(other, 'prompt', inPrompt)
    rewriteScalar(other, 'action', inPrompt)
    for (const child of childrenOf(other)) rewriteScalar(child, 'prompt', inPrompt)
  }
  const agents = doc.get('agents', true)
  if (isMap(agents))
    for (const pair of agents.items)
      if (isMap(pair.value)) rewriteScalar(pair.value, 'prompt', inPrompt)
  return render(text, doc)
}

/**
 * Hace que `stage` dependa de `dependency`. Se rechaza si ya depende, si es ella misma o si
 * formaría un ciclo (`dependency` ya depende, directa o indirectamente, de `stage`).
 */
export function addDependency(
  text: string,
  stage: string,
  dependency: string,
  optional = false,
): string {
  const doc = parse(text)
  const target = findStage(doc, stage)
  findStage(doc, dependency)
  if (stage === dependency) throw new EditError('Una fase no puede depender de sí misma')
  const deps = dependenciesOf(target)
  if (deps.some((d) => d.stage === dependency))
    throw new EditError(`\`${stage}\` ya depende de \`${dependency}\``)
  const cycle = dependencyPath(readStages(text), dependency, stage)
  if (cycle) throw new EditError(`Así se formaría un ciclo: ${[stage, ...cycle].join(' → ')}`)
  writeDependencies(doc, target, [...deps, { stage: dependency, optional }])
  return render(text, doc)
}

/** Quita la dependencia de `stage` en `dependency`, y el `workspaceFrom` que la usaba. */
export function removeDependency(text: string, stage: string, dependency: string): string {
  const doc = parse(text)
  const target = findStage(doc, stage)
  const deps = dependenciesOf(target)
  if (!deps.some((d) => d.stage === dependency))
    throw new EditError(`\`${stage}\` no depende de \`${dependency}\``)
  writeDependencies(
    doc,
    target,
    deps.filter((d) => d.stage !== dependency),
  )
  const from = target.get('workspaceFrom')
  if (typeof from === 'string' && (from === dependency || from.startsWith(`${dependency}.`)))
    target.delete('workspaceFrom')
  return render(text, doc)
}

/** Marca una dependencia como opcional (`plan?`: también vale si se omite) o como obligatoria. */
export function setDependencyOptional(
  text: string,
  stage: string,
  dependency: string,
  optional: boolean,
): string {
  const doc = parse(text)
  const target = findStage(doc, stage)
  const deps = dependenciesOf(target)
  if (!deps.some((d) => d.stage === dependency))
    throw new EditError(`\`${stage}\` no depende de \`${dependency}\``)
  writeDependencies(
    doc,
    target,
    deps.map((d) => (d.stage === dependency ? { ...d, optional } : d)),
  )
  return render(text, doc)
}

/** Cambia un campo de una fase; null o '' lo quitan. El `id` se cambia con `renameStage`. */
export function setStageField(text: string, id: string, field: StageField, value: Value): string {
  const doc = parse(text)
  setValue(findStage(doc, id), field, value)
  return render(text, doc)
}

function findChild(stage: YAMLMap, stageId: string, childId: string): YAMLMap {
  const child = childrenOf(stage).find((c) => childIdOf(c) === childId)
  if (!child) throw new EditError(`La fase \`${stageId}\` no tiene ningún hijo \`${childId}\``)
  return child
}

/**
 * Añade un hijo a una fase `parallel`. Sin `childId` lleva el nombre de su agente; si ese ya está,
 * se le pone un `id` libre.
 */
export function addChild(
  text: string,
  stageId: string,
  agent: string,
  childId?: string,
): { text: string; id: string } {
  const doc = parse(text)
  const stage = findStage(doc, stageId)
  if (stage.get('type') !== 'parallel')
    throw new EditError(`\`${stageId}\` no es una fase \`parallel\``)
  const taken = new Set(
    childrenOf(stage)
      .map(childIdOf)
      .filter((c): c is string => c != null),
  )
  const id = childId ?? freeId(taken, agent)
  checkId(id, 'el hijo')
  if (taken.has(id)) throw new EditError(`La fase \`${stageId}\` ya tiene un hijo \`${id}\``)
  const child = doc.createNode(id === agent ? { agent } : { id, agent }) as YAMLMap
  const children = stage.get('children', true)
  if (isSeq(children)) {
    // Una lista vacía `[]` pasa a ser de guiones: los hijos son mapas de varias claves.
    if (children.items.length === 0) children.flow = false
    children.items.push(child)
  } else stage.set('children', doc.createNode([child.toJSON()]))
  return { text: render(text, doc), id }
}

/** Quita un hijo de una fase `parallel`, y el `workspaceFrom` de otras fases que lo continuaba. */
export function removeChild(text: string, stageId: string, childId: string): string {
  const doc = parse(text)
  const stage = findStage(doc, stageId)
  const child = findChild(stage, stageId, childId)
  const children = stage.get('children', true)
  if (isSeq(children)) children.items.splice(children.items.indexOf(child), 1)
  for (const other of stageMaps(doc))
    if (other.get('workspaceFrom') === `${stageId}.${childId}`) other.delete('workspaceFrom')
  return render(text, doc)
}

/** Cambia el agente o el prompt de un hijo de una `parallel`; null o '' quitan el prompt. */
export function setChildField(
  text: string,
  stageId: string,
  childId: string,
  field: ChildField,
  value: string | null,
): string {
  const doc = parse(text)
  const child = findChild(findStage(doc, stageId), stageId, childId)
  if (field === 'agent') {
    if (!value) throw new EditError('Un hijo necesita su agente')
    // Sin `id` propio, el hijo se llama como su agente: se fija para no cambiarle el nombre.
    if (!child.has('id') && value !== childId) {
      child.items.unshift(doc.createPair('id', childId))
    }
  }
  setValue(child, field, value)
  return render(text, doc)
}
