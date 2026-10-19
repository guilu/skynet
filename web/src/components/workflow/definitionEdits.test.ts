import { parse } from 'yaml'
import { describe, expect, it } from 'vitest'
import {
  addAgent,
  addChild,
  addInput,
  addDependency,
  addStage,
  dependencyPath,
  dependentsOf,
  EditError,
  keepUntouchedLines,
  readAgentNames,
  readAgents,
  readInputs,
  readRoot,
  readChildIds,
  readStage,
  readStages,
  removeAgent,
  removeChild,
  removeInput,
  removeDependency,
  removeStage,
  renameAgent,
  renameInput,
  renameStage,
  setAgentField,
  setAgentLimit,
  setAgentTools,
  setChildField,
  setInputField,
  setDependencyOptional,
  setNamedChecks,
  setRetryAttempts,
  setRootField,
  setStageField,
  STAGE_TYPES,
  stageLine,
} from './definitionEdits'
import { TEMPLATES } from './templates'

const BASE = `id: demo
# Un comentario arriba
agents:
  dev:
    prompt: |
      Implementa {{workItem.key}}.
  revisor:
    prompt: Revisa {{stages.implement.output.summary}}

stages:
  - id: implement   # la primera
    type: agent
    agent: dev
  - id: review
    type: agent
    agent: revisor
    dependsOn: [implement]
    outputSchema: {type: object, properties: {blocking: {type: boolean}}}
  - id: fix
    type: agent
    prompt: "Corrige: {{stages.review.output.blocking}}"
    dependsOn: [review]
    condition: review.blocking && !reviewer.done
  - id: tests
    type: command
    command: ./gradlew test  # dos espacios antes
    dependsOn:
      - review
      - fix?
    workspaceFrom: review
`

const stageOf = (text: string, id: string) =>
  (parse(text) as { stages: Record<string, unknown>[] }).stages.find((s) => s.id === id)!

describe('keepUntouchedLines', () => {
  it('conserva las líneas que no cambian aunque yaml las normalice', () => {
    const original = 'a: 1  # dos\nb: 2\n'
    expect(keepUntouchedLines(original, 'a: 1 # dos\nb: 3\n')).toBe('a: 1  # dos\nb: 3\n')
  })

  it('las plantillas no cambian al editar algo que no las toca', () => {
    for (const template of TEMPLATES) {
      const first = readStages(template.yaml)[0].id
      const renamed = renameStage(template.yaml, first, 'tmp-x')
      expect(renameStage(renamed, 'tmp-x', first)).toBe(template.yaml)
    }
  })
})

describe('addStage', () => {
  it('añade al final con un id libre y lo mínimo de su tipo', () => {
    const { text, id } = addStage(BASE, 'command')
    expect(id).toBe('command')
    expect(stageOf(text, 'command')).toEqual({ id: 'command', type: 'command', command: '' })
    expect(text.startsWith(BASE.trimEnd())).toBe(true)
    expect(text).toContain('# Un comentario arriba')
  })

  it('con after va detrás de esa fase y depende de ella', () => {
    const { text, id } = addStage(BASE, 'agent', { after: 'implement' })
    expect(readStages(text).map((s) => s.id)).toEqual(['implement', id, 'review', 'fix', 'tests'])
    expect(text).toContain(
      `  - id: agent\n    type: agent\n    prompt: ""\n    dependsOn: [implement]\n`,
    )
  })

  it('no repite un id', () => {
    const once = addStage(BASE, 'parallel').text
    const twice = addStage(once, 'parallel')
    expect(twice.id).toBe('parallel-2')
    expect(stageOf(twice.text, 'parallel-2')).toEqual({
      id: 'parallel-2',
      type: 'parallel',
      children: [],
    })
  })

  it('sabe crear cada tipo de la paleta', () => {
    for (const type of STAGE_TYPES) {
      const { text, id } = addStage(BASE, type)
      expect(stageOf(text, id).type).toBe(type)
    }
    expect(stageOf(addStage(BASE, 'verification').text, 'verification').checks).toEqual(['tests'])
    expect(addStage(BASE, 'human-approval').id).toBe('aprobacion')
  })

  it('rechaza un YAML con errores de sintaxis', () => {
    expect(() => addStage('stages: [a', 'agent')).toThrow(EditError)
  })
})

describe('removeStage', () => {
  it('quita la fase y sus referencias en dependsOn, workspaceFrom y revise', () => {
    const text = removeStage(BASE, 'review')
    expect(readStages(text).map((s) => s.id)).toEqual(['implement', 'fix', 'tests'])
    expect(stageOf(text, 'fix').dependsOn).toBeUndefined()
    expect(stageOf(text, 'tests').dependsOn).toEqual(['fix?'])
    expect(stageOf(text, 'tests').workspaceFrom).toBeUndefined()
    expect(text).toContain('command: ./gradlew test  # dos espacios antes')
    expect(text).toContain('  - id: implement   # la primera')
  })

  it('no deja borrar la única fase', () => {
    expect(() => removeStage('id: x\nstages:\n  - id: a\n    type: agent\n', 'a')).toThrow(
      /única fase/,
    )
  })

  it('dice qué fases dependían de ella', () => {
    expect(dependentsOf(BASE, 'review')).toEqual(['fix', 'tests'])
  })
})

describe('renameStage', () => {
  it('cambia el id y todo lo que lo nombra', () => {
    const text = renameStage(BASE, 'review', 'revision')
    expect(stageOf(text, 'revision')).toBeTruthy()
    expect(stageOf(text, 'fix').dependsOn).toEqual(['revision'])
    expect(stageOf(text, 'fix').condition).toBe('revision.blocking && !reviewer.done')
    expect(stageOf(text, 'fix').prompt).toBe('Corrige: {{stages.revision.output.blocking}}')
    expect(stageOf(text, 'tests').dependsOn).toEqual(['revision', 'fix?'])
    expect(stageOf(text, 'tests').workspaceFrom).toBe('revision')
    // Conserva el estilo de cada escalar.
    expect(text).toContain('prompt: "Corrige: {{stages.revision.output.blocking}}"')
  })

  it('cambia los datos de fases en los prompts de los agentes y conserva las opcionales', () => {
    const agent = renameStage(BASE, 'implement', 'build')
    expect(agent).toContain('prompt: Revisa {{stages.build.output.summary}}')
    const optional = renameStage(BASE, 'fix', 'arreglo')
    expect(stageOf(optional, 'tests').dependsOn).toEqual(['review', 'arreglo?'])
    expect(optional).toContain('      - arreglo?\n')
  })

  it('cambia workspaceFrom de un hijo y revise', () => {
    const text = `id: x
stages:
  - id: review
    type: parallel
    children: [{agent: a}]
  - id: gate
    type: human-approval
    action: Ok
    revise: review
    dependsOn: [review]
    workspaceFrom: review.a
`
    const renamed = renameStage(text, 'review', 'rev')
    expect(stageOf(renamed, 'gate').revise).toBe('rev')
    expect(stageOf(renamed, 'gate').workspaceFrom).toBe('rev.a')
  })

  it('rechaza un id repetido o mal formado', () => {
    expect(() => renameStage(BASE, 'review', 'fix')).toThrow(/Ya hay una fase/)
    expect(() => renameStage(BASE, 'review', 'Mal id')).toThrow(EditError)
    expect(() => renameStage(BASE, 'nada', 'algo')).toThrow(/No hay ninguna fase/)
  })
})

describe('dependencias', () => {
  it('añade a una lista entre corchetes conservando su estilo', () => {
    const text = addDependency(BASE, 'fix', 'implement')
    expect(text).toContain('    dependsOn: [review, implement]\n')
  })

  it('añade a una lista de guiones y crea la lista si no hay', () => {
    const dashes = addDependency(BASE, 'tests', 'implement', true)
    expect(dashes).toContain('      - review\n      - fix?\n      - implement?\n')
    const created = addDependency(addStage(BASE, 'command').text, 'command', 'tests', true)
    expect(created).toContain('dependsOn: ["tests?"]')
  })

  it('rechaza un ciclo con el camino, una repetida y a sí misma', () => {
    expect(() => addDependency(BASE, 'implement', 'tests')).toThrow(
      'Así se formaría un ciclo: implement → tests → review → implement',
    )
    expect(() => addDependency(BASE, 'fix', 'review')).toThrow(/ya depende/)
    expect(() => addDependency(BASE, 'fix', 'fix')).toThrow(/sí misma/)
  })

  it('quita una dependencia, la clave si queda vacía y su workspaceFrom', () => {
    expect(stageOf(removeDependency(BASE, 'fix', 'review'), 'fix').dependsOn).toBeUndefined()
    const tests = stageOf(removeDependency(BASE, 'tests', 'review'), 'tests')
    expect(tests.dependsOn).toEqual(['fix?'])
    expect(tests.workspaceFrom).toBeUndefined()
  })

  it('cambia una dependencia entre opcional y obligatoria', () => {
    const optional = setDependencyOptional(BASE, 'fix', 'review', true)
    expect(optional).toContain('dependsOn: ["review?"]')
    expect(setDependencyOptional(optional, 'fix', 'review', false)).toBe(BASE)
    expect(stageOf(setDependencyOptional(BASE, 'tests', 'fix', false), 'tests').dependsOn).toEqual([
      'review',
      'fix',
    ])
  })

  it('encuentra el camino de dependencias', () => {
    const stages = readStages(BASE)
    expect(dependencyPath(stages, 'tests', 'implement')).toEqual(['tests', 'review', 'implement'])
    expect(dependencyPath(stages, 'implement', 'tests')).toBeNull()
  })
})

describe('setStageField', () => {
  it('cambia, añade y quita campos conservando el estilo', () => {
    const changed = setStageField(BASE, 'tests', 'command', 'npm test')
    expect(changed).toContain('command: npm test  # dos espacios antes')
    const added = setStageField(BASE, 'implement', 'phase', 'implement')
    expect(stageOf(added, 'implement').phase).toBe('implement')
    expect(stageOf(setStageField(BASE, 'fix', 'condition', null), 'fix').condition).toBeUndefined()
    expect(stageOf(setStageField(BASE, 'fix', 'condition', ''), 'fix').condition).toBeUndefined()
  })

  it('un texto de varias líneas va en bloque', () => {
    const text = setStageField(BASE, 'implement', 'prompt', 'Uno\nDos\n')
    expect(text).toContain('    prompt: |\n      Uno\n      Dos\n')
    expect(stageOf(text, 'implement').prompt).toBe('Uno\nDos\n')
    const back = setStageField(text, 'implement', 'prompt', 'Una línea')
    expect(back).toContain('    prompt: Una línea\n')
  })
})

describe('hijos de una parallel', () => {
  const PARALLEL = addStage(BASE, 'parallel', { after: 'implement' }).text

  it('añade hijos con el nombre de su agente o uno libre', () => {
    const one = addChild(PARALLEL, 'parallel', 'revisor')
    expect(one.id).toBe('revisor')
    const two = addChild(one.text, 'parallel', 'revisor')
    expect(two.id).toBe('revisor-2')
    expect(stageOf(two.text, 'parallel').children).toEqual([
      { agent: 'revisor' },
      { id: 'revisor-2', agent: 'revisor' },
    ])
    expect(two.text).toContain('    children:\n      - agent: revisor\n')
    expect(() => addChild(BASE, 'fix', 'dev')).toThrow(/no es una fase `parallel`/)
  })

  it('quita un hijo y el workspaceFrom que lo continuaba', () => {
    const withChild = addChild(PARALLEL, 'parallel', 'revisor').text
    const pointing = setStageField(withChild, 'review', 'workspaceFrom', 'parallel.revisor')
    const removed = removeChild(pointing, 'parallel', 'revisor')
    expect(stageOf(removed, 'parallel').children).toEqual([])
    expect(stageOf(removed, 'review').workspaceFrom).toBeUndefined()
  })

  it('cambiar el agente de un hijo sin id le fija el id', () => {
    const withChild = addChild(PARALLEL, 'parallel', 'revisor').text
    const changed = setChildField(withChild, 'parallel', 'revisor', 'agent', 'dev')
    expect(stageOf(changed, 'parallel').children).toEqual([{ id: 'revisor', agent: 'dev' }])
    const prompt = setChildField(changed, 'parallel', 'revisor', 'prompt', 'Mira esto')
    expect(stageOf(prompt, 'parallel').children).toEqual([
      { id: 'revisor', agent: 'dev', prompt: 'Mira esto' },
    ])
    expect(() => setChildField(withChild, 'parallel', 'nada', 'prompt', 'x')).toThrow(
      /no tiene ningún hijo/,
    )
  })
})

describe('para el panel de propiedades', () => {
  it('lee una fase, los agentes y los hijos tal como están en el texto', () => {
    expect(readStage(BASE, 'tests')).toMatchObject({
      type: 'command',
      dependsOn: ['review', 'fix?'],
      workspaceFrom: 'review',
    })
    expect(readStage(BASE, 'nada')).toBeNull()
    expect(readStage('stages: [', 'a')).toBeNull()
    expect(readAgentNames(BASE)).toEqual(['dev', 'revisor'])
    const parallel = addChild(addStage(BASE, 'parallel').text, 'parallel', 'dev').text
    expect(readChildIds(parallel, 'parallel')).toEqual(['dev'])
  })

  it('cambia los intentos de retry sin tocar lo demás de retry', () => {
    const added = setRetryAttempts(BASE, 'implement', 3)
    expect(added).toContain('    retry: {maxAttempts: 3}\n')
    const withOn = added.replace('{maxAttempts: 3}', '{maxAttempts: 3, on: [lost]}')
    expect(stageOf(setRetryAttempts(withOn, 'implement', 2), 'implement').retry).toEqual({
      maxAttempts: 2,
      on: ['lost'],
    })
    expect(stageOf(setRetryAttempts(withOn, 'implement', null), 'implement').retry).toBeUndefined()
  })

  it('marca las comprobaciones con nombre y conserva las de comando', () => {
    const text = setStageField(BASE, 'implement', 'phase', 'implement').replace(
      '    phase: implement\n',
      '    checks: [commit, {command: npm run lint}]\n',
    )
    expect(
      stageOf(setNamedChecks(text, 'implement', ['tests', 'commit']), 'implement').checks,
    ).toEqual(['tests', 'commit', { command: 'npm run lint' }])
    expect(stageOf(setNamedChecks(text, 'implement', []), 'implement').checks).toEqual([
      { command: 'npm run lint' },
    ])
    expect(stageOf(setNamedChecks(BASE, 'fix', []), 'fix').checks).toBeUndefined()
    expect(setNamedChecks(BASE, 'fix', ['diff'])).toContain('    checks: [diff]\n')
  })

  it('dice la línea de una fase y de su campo', () => {
    expect(stageLine(BASE, 'review')).toEqual({ line: 14, column: 5 })
    expect(stageLine(BASE, 'review', 'dependsOn')).toEqual({ line: 17, column: 5 })
    expect(stageLine(BASE, 'review', 'nada')).toEqual({ line: 14, column: 5 })
    expect(stageLine(BASE, 'nada')).toBeNull()
  })
})

describe('agentes, entradas y raíz', () => {
  const WITH_INPUTS = BASE.replace(
    '# Un comentario arriba\n',
    '# Un comentario arriba\ninputs:\n  foco:\n    type: string\n',
  ).replace('Implementa {{workItem.key}}.', 'Implementa {{workItem.key}} con {{ inputs.foco }}.')

  it('añade un agente delante de stages y lo quita si nadie lo usa', () => {
    const noAgents = 'id: x\nstages:\n  - id: a\n    type: agent\n'
    const added = addAgent(noAgents)
    expect(added.name).toBe('agente')
    expect(added.text).toBe(
      'id: x\nagents:\n  agente:\n    prompt: ""\nstages:\n  - id: a\n    type: agent\n',
    )
    expect(addAgent(added.text).name).toBe('agente-2')
    expect(removeAgent(added.text, 'agente')).toBe(noAgents)
    expect(() => removeAgent(BASE, 'dev')).toThrow('No se puede quitar `dev`: lo usa implement')
  })

  it('renombra un agente en las fases y fija el id de los hijos que se llamaban como él', () => {
    const parallel = addChild(addStage(BASE, 'parallel').text, 'parallel', 'revisor').text
    const renamed = renameAgent(parallel, 'revisor', 'critico')
    expect(readAgentNames(renamed)).toEqual(['dev', 'critico'])
    expect(stageOf(renamed, 'review').agent).toBe('critico')
    expect(stageOf(renamed, 'parallel').children).toEqual([{ id: 'revisor', agent: 'critico' }])
    expect(renamed).toContain('# Un comentario arriba')
    expect(() => renameAgent(BASE, 'dev', 'revisor')).toThrow(/Ya hay un agente/)
  })

  it('cambia los campos, las herramientas y los límites de un agente', () => {
    let text = setAgentField(BASE, 'revisor', 'provider', 'codex')
    text = setAgentField(text, 'revisor', 'permissionMode', 'plan')
    text = setAgentTools(text, 'revisor', ['Read', 'Grep'])
    text = setAgentLimit(text, 'revisor', 'maxTurns', 20)
    text = setAgentLimit(text, 'revisor', 'timeoutMinutes', 15)
    expect(readAgents(text).find((a) => a.name === 'revisor')!.fields).toEqual({
      prompt: 'Revisa {{stages.implement.output.summary}}',
      provider: 'codex',
      permissionMode: 'plan',
      tools: ['Read', 'Grep'],
      limits: { maxTurns: 20, timeoutMinutes: 15 },
    })
    expect(text).toContain('    tools: [Read, Grep]\n')
    text = setAgentLimit(
      setAgentLimit(text, 'revisor', 'maxTurns', null),
      'revisor',
      'timeoutMinutes',
      null,
    )
    text = setAgentTools(setAgentField(text, 'revisor', 'provider', null), 'revisor', [])
    expect(readAgents(text).find((a) => a.name === 'revisor')!.fields).toEqual({
      prompt: 'Revisa {{stages.implement.output.summary}}',
      permissionMode: 'plan',
    })
  })

  it('añade, renombra (con sus usos) y quita entradas', () => {
    const added = addInput(BASE)
    expect(added.name).toBe('dato')
    expect(readInputs(added.text)).toEqual([{ name: 'dato', fields: { type: 'string' } }])
    const renamed = renameInput(WITH_INPUTS, 'foco', 'tema')
    expect(renamed).toContain('Implementa {{workItem.key}} con {{ inputs.tema }}.')
    expect(readInputs(renamed).map((i) => i.name)).toEqual(['tema'])
    expect(() => renameInput(WITH_INPUTS, 'foco', '1x')).toThrow(EditError)
    expect(removeInput(WITH_INPUTS, 'foco')).not.toContain('inputs:')
  })

  it('escribe el valor por defecto con el tipo de la entrada', () => {
    let text = setInputField(WITH_INPUTS, 'foco', 'type', 'number')
    text = setInputField(text, 'foco', 'default', '3')
    text = setInputField(text, 'foco', 'required', true)
    expect(readInputs(text)[0].fields).toEqual({ type: 'number', default: 3, required: true })
    text = setInputField(setInputField(text, 'foco', 'type', 'boolean'), 'foco', 'default', 'true')
    expect(readInputs(text)[0].fields.default).toBe(true)
    expect(
      readInputs(setInputField(text, 'foco', 'required', false))[0].fields.required,
    ).toBeUndefined()
  })

  it('lee y cambia el id, el nombre y la descripción del workflow', () => {
    expect(readRoot(BASE)).toEqual({ id: 'demo', name: '', description: '' })
    const named = setRootField(setRootField(BASE, 'name', 'Demo'), 'id', 'otro-demo')
    expect(named.startsWith('id: otro-demo\nname: Demo\n# Un comentario arriba\nagents:')).toBe(
      true,
    )
    expect(setRootField(named, 'description', 'Hace algo')).toContain(
      'name: Demo\ndescription: Hace algo\n',
    )
    expect(readRoot(named)).toEqual({ id: 'otro-demo', name: 'Demo', description: '' })
    expect(() => setRootField(BASE, 'id', 'X')).toThrow(EditError)
  })
})
