import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState, type ReactNode } from 'react'
import {
  AlertTriangle,
  Check,
  FileText,
  GitBranch,
  GitCommitHorizontal,
  HardDrive,
  Info,
  Link2,
  Package,
  RefreshCw,
  ShieldCheck,
} from 'lucide-react'
import { Link, useNavigate } from 'react-router'
import { api, type CatalogEntry, type RunnerCatalog } from '../api'
import { Companion } from '../components/companion/Companion'
import { ErrorMessage } from '../components/ErrorMessage'
import { SearchField } from '../components/list/Toolbar'
import { Button } from '../components/ui/Button'
import { Pill } from '../components/ui/Pill'
import {
  KEY_PATTERN,
  branchSource,
  importRequest,
  initialForm,
  matchesSearch,
  nameSource,
  proposedDescription,
  remoteSource,
  shortPath,
  shortRemote,
  toolLabel,
  validationSource,
  type ImportForm,
  type ImportedState,
} from '../components/project/importWizard'
import { formatDateTime } from '../format'

const STEPS = ['Repositorio', 'Proyecto', 'Configuración', 'Resumen'] as const
const LEARN_WORKFLOW = 'conocer-repositorio'
/** Cuánto se espera al catálogo nuevo tras «Volver a buscar». */
const REFRESH_WAIT_MS = 45_000

function plural(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`
}

/** Si algún runner ha mandado su catálogo después de `since`. */
function freshSince(data: RunnerCatalog[] | undefined, since: number): boolean {
  return !!data?.some((r) => new Date(r.receivedAt).getTime() >= since)
}

interface Selected {
  runner: RunnerCatalog
  entry: CatalogEntry
}

/**
 * Asistente «Importar proyecto» (IP-C): elige un repositorio del catálogo de los runners, revisa lo
 * que se dedujo de él y crea el proyecto y su repositorio de una vez.
 */
export function ImportProjectPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [step, setStep] = useState(1)
  const [q, setQ] = useState('')
  const [selected, setSelected] = useState<Selected | null>(null)
  const [form, setForm] = useState<ImportForm | null>(null)
  const [learn, setLearn] = useState(false)
  const [waitingSince, setWaitingSince] = useState<number | null>(null)

  const catalog = useQuery({
    queryKey: ['repository-catalog'],
    queryFn: api.repositoryCatalog,
    refetchInterval: (query) =>
      waitingSince != null && !freshSince(query.state.data, waitingSince) ? 3_000 : false,
  })
  const waiting = waitingSince != null && !freshSince(catalog.data, waitingSince)

  const refresh = useMutation({
    mutationFn: api.refreshRepositoryCatalog,
    onSuccess: () => {
      const since = Date.now()
      setWaitingSince(since)
      // Si ningún runner responde, se deja de esperar.
      setTimeout(() => setWaitingSince((w) => (w === since ? null : w)), REFRESH_WAIT_MS)
    },
  })

  const choose = async (runner: RunnerCatalog, entry: CatalogEntry) => {
    setSelected({ runner, entry })
    const base = initialForm(entry)
    setForm(base)
    try {
      const { key } = await api.keySuggestion(base.name)
      setForm((f) => (f && f.key === '' ? { ...f, key } : f))
    } catch {
      // Sin sugerencia: la clave se escribe a mano.
    }
  }

  const create = useMutation({
    mutationFn: async () => {
      if (!selected || !form) throw new Error('Elige un repositorio')
      const imported = await api.importProject(importRequest(selected.entry.repository.path, form))
      const state: ImportedState = { importedFrom: selected.entry.repository.path }
      if (learn) {
        try {
          const workflows = await api.workflows()
          const workflow = workflows.find((w) => w.key === LEARN_WORKFLOW && w.published)
          if (!workflow?.published) {
            state.learn = 'missing'
          } else {
            const item = await api.createWorkItem(imported.project.id, {
              title: 'Conocer el repositorio',
              type: 'FEATURE',
              repositoryId: imported.repository.id,
            })
            const run = await api.launchRun(item.id, {
              repositoryId: imported.repository.id,
              definitionId: workflow.published.id,
            })
            state.learn = 'launched'
            state.learnRunId = run.id
          }
        } catch {
          state.learn = 'failed'
        }
      }
      return { imported, state }
    },
    onSuccess: ({ imported, state }) => {
      void queryClient.invalidateQueries({ queryKey: ['projects'] })
      void queryClient.invalidateQueries({ queryKey: ['repository-catalog'] })
      void navigate(`/projects/${imported.project.id}`, { state })
    },
  })

  const runners = catalog.data ?? []
  const total = runners.reduce((n, r) => n + r.repositories.length, 0)
  const keyValid = !!form && KEY_PATTERN.test(form.key)
  const canNext =
    step === 1
      ? !!selected
      : step === 2
        ? !!form && keyValid && form.name.trim() !== ''
        : step === 3
          ? !!form && form.repoName.trim() !== ''
          : true

  return (
    <>
      <div className="page-head">
        <div className="page-title">
          <h1>Importar proyecto</h1>
          <p className="muted">
            Crea un proyecto a partir de un repositorio que ya está clonado en el disco del runner.
          </p>
        </div>
      </div>
      <nav className="import-steps" aria-label="Pasos">
        <ol>
          {STEPS.map((s, i) => (
            <li
              key={s}
              className={i + 1 === step ? 'is-current' : i + 1 < step ? 'is-done' : undefined}
              aria-current={i + 1 === step ? 'step' : undefined}
            >
              <span className="import-step-n" aria-hidden="true">
                {i + 1 < step ? <Check size={14} strokeWidth={3} /> : i + 1}
              </span>
              <span className="import-step-label">{s}</span>
            </li>
          ))}
        </ol>
      </nav>

      <div className="import-wizard">
        <section className="import-main" aria-label={STEPS[step - 1]}>
          {step === 1 && (
            <ChooseStep
              runners={runners}
              total={total}
              loading={catalog.isPending}
              q={q}
              setQ={setQ}
              selected={selected?.entry.repository.path ?? null}
              onChoose={(r, e) => void choose(r, e)}
              onRefresh={() => refresh.mutate()}
              refreshing={refresh.isPending || waiting}
            />
          )}
          {step === 2 && selected && form && (
            <ProjectStep entry={selected.entry} form={form} setForm={setForm} keyValid={keyValid} />
          )}
          {step === 3 && selected && form && (
            <RepositoryStep entry={selected.entry} form={form} setForm={setForm} />
          )}
          {step === 4 && selected && form && (
            <SummaryStep
              path={selected.entry.repository.path}
              form={form}
              learn={learn}
              setLearn={setLearn}
              onEdit={setStep}
            />
          )}
          <ErrorMessage error={catalog.error ?? refresh.error ?? create.error} />
          {(step > 1 || runners.length > 0) && (
            <div className="import-nav">
              {step > 1 ? (
                <Button variant="secondary" onClick={() => setStep(step - 1)}>
                  Atrás
                </Button>
              ) : (
                <span />
              )}
              {step < 4 ? (
                <Button onClick={() => setStep(step + 1)} disabled={!canNext}>
                  Siguiente
                </Button>
              ) : (
                <Button onClick={() => create.mutate()} disabled={create.isPending}>
                  {create.isPending ? 'Creando…' : 'Crear proyecto'}
                </Button>
              )}
            </div>
          )}
        </section>
        <Aside step={step} entry={selected?.entry ?? null} />
      </div>
    </>
  )
}

function ChooseStep({
  runners,
  total,
  loading,
  q,
  setQ,
  selected,
  onChoose,
  onRefresh,
  refreshing,
}: {
  runners: RunnerCatalog[]
  total: number
  loading: boolean
  q: string
  setQ: (q: string) => void
  selected: string | null
  onChoose: (runner: RunnerCatalog, entry: CatalogEntry) => void
  onRefresh: () => void
  refreshing: boolean
}) {
  if (loading) return <p className="muted">Cargando los repositorios…</p>
  if (runners.length === 0) {
    return (
      <div className="card import-empty">
        <Companion state="thinking" size={96} />
        <h2>El runner todavía no busca repositorios</h2>
        <p className="muted">
          Dile en qué carpetas están tus repositorios añadiendo esta línea a{' '}
          <code>~/.config/skynet-runner/runner.env</code> y reinícialo:
        </p>
        <pre className="import-code">SKYNET_REPO_ROOTS=$HOME/code</pre>
        <p className="muted">
          Mientras tanto puedes crear el proyecto a mano desde <Link to="/projects">Proyectos</Link>
          .
        </p>
      </div>
    )
  }
  return (
    <>
      <div className="import-toolbar">
        <SearchField
          label="Buscar repositorios"
          placeholder="Buscar por nombre o ruta"
          value={q}
          onChange={setQ}
        />
        <Button variant="secondary" size="sm" onClick={onRefresh} disabled={refreshing}>
          <RefreshCw size={16} strokeWidth={2.5} aria-hidden="true" />
          {refreshing ? 'Buscando…' : 'Volver a buscar'}
        </Button>
      </div>
      {runners.map((runner) => {
        const visible = runner.repositories.filter((e) => matchesSearch(e, q))
        return (
          <div key={runner.runnerId} className="import-runner">
            <div
              className="card import-list"
              role="radiogroup"
              aria-label={`Repositorios de ${runner.runnerName}`}
            >
              {visible.length === 0 && (
                <p className="muted import-none">Ningún repositorio coincide con la búsqueda.</p>
              )}
              {visible.map((entry) => (
                <Candidate
                  key={entry.repository.path}
                  entry={entry}
                  checked={selected === entry.repository.path}
                  onChoose={() => onChoose(runner, entry)}
                />
              ))}
            </div>
            <p className="muted small">
              Runner <strong>{runner.runnerName}</strong> · buscó en{' '}
              {runner.roots.map(shortPath).join(', ')} el {formatDateTime(runner.scannedAt)} ·{' '}
              {plural(runner.repositories.length, 'repositorio', 'repositorios')},{' '}
              {runner.repositories.filter((e) => e.registered).length} ya en Skynet
              {runner.truncated && ' · se paró en el máximo: acota SKYNET_REPO_ROOTS'}
            </p>
          </div>
        )
      })}
      {total === 0 && (
        <p className="muted">
          No hay repositorios git en esas carpetas. Clona alguno y pulsa «Volver a buscar».
        </p>
      )}
    </>
  )
}

function Candidate({
  entry,
  checked,
  onChoose,
}: {
  entry: CatalogEntry
  checked: boolean
  onChoose: () => void
}) {
  const r = entry.repository
  const taken = entry.registered
  const tool = toolLabel(r.tool)
  return (
    <label className={`import-cand${checked ? ' is-selected' : ''}${taken ? ' is-taken' : ''}`}>
      <input
        type="radio"
        name="repository"
        checked={checked}
        disabled={!!taken}
        onChange={onChoose}
      />
      <span className="import-cand-body">
        <span className="import-cand-name">
          <strong>{r.name}</strong>
          <span className="chip">
            <Package size={13} aria-hidden="true" />
            {tool ?? 'Sin herramienta reconocida'}
          </span>
          {r.dirty && <Pill tone="warn">Cambios sin commitear</Pill>}
          {taken && (
            <Pill tone="neutral">
              Ya está en <Link to={`/projects/${taken.projectId}`}>{taken.projectKey}</Link>
            </Pill>
          )}
        </span>
        <span className="import-cand-meta">
          <code>{shortPath(r.path)}</code>
          <span>
            {r.remoteUrl ? (
              <Link2 size={13} aria-hidden="true" />
            ) : (
              <AlertTriangle size={13} aria-hidden="true" />
            )}
            {r.remoteUrl ? shortRemote(r.remoteUrl) : 'Sin remoto'}
          </span>
          <span>
            <GitBranch size={13} aria-hidden="true" />
            {r.defaultBranch}
          </span>
        </span>
      </span>
      {r.lastCommitAt && (
        <span className="import-cand-when muted">
          último commit
          <br />
          {formatDateTime(r.lastCommitAt)}
        </span>
      )}
    </label>
  )
}

function Field({
  label,
  source,
  ok,
  children,
}: {
  label: string
  source: string | null
  ok?: boolean
  children: ReactNode
}) {
  return (
    <label className="import-field">
      {label}
      {children}
      {source && (
        <span className={`import-source${ok ? ' is-ok' : ''}`}>
          {ok ? <Check size={13} aria-hidden="true" /> : <Info size={13} aria-hidden="true" />}
          {source}
        </span>
      )}
    </label>
  )
}

type SetForm = (update: (f: ImportForm | null) => ImportForm | null) => void

function useField(setForm: SetForm) {
  return (k: keyof ImportForm) => (e: { target: { value: string } }) =>
    setForm((f) =>
      f ? { ...f, [k]: k === 'key' ? e.target.value.toUpperCase() : e.target.value } : f,
    )
}

function ProjectStep({
  entry,
  form,
  setForm,
  keyValid,
}: {
  entry: CatalogEntry
  form: ImportForm
  setForm: SetForm
  keyValid: boolean
}) {
  const on = useField(setForm)
  const description = proposedDescription(entry)
  const github = entry.github?.description
  return (
    <div className="card form import-form">
      <div className="import-row">
        <Field
          label="Clave"
          source={
            keyValid ? 'Libre · sale del nombre' : '2-10 mayúsculas y dígitos, empezando por letra'
          }
          ok={keyValid}
        >
          <input value={form.key} onChange={on('key')} required className="mono" />
        </Field>
        <Field label="Nombre" source={nameSource(entry.repository)}>
          <input value={form.name} onChange={on('name')} required />
        </Field>
      </div>
      <Field
        label="Descripción"
        source={description.source ? `De ${description.source}` : 'No se encontró ninguna'}
      >
        <textarea rows={3} value={form.description} onChange={on('description')} />
      </Field>
      {github && github !== form.description && (
        <p className="import-note">
          <Info size={16} aria-hidden="true" />
          <span>
            En GitHub pone «{github}».{' '}
            <Button
              variant="link"
              onClick={() => setForm((f) => (f ? { ...f, description: github } : f))}
            >
              Usar esa
            </Button>
          </span>
        </p>
      )}
    </div>
  )
}

function RepositoryStep({
  entry,
  form,
  setForm,
}: {
  entry: CatalogEntry
  form: ImportForm
  setForm: SetForm
}) {
  const on = useField(setForm)
  const r = entry.repository
  const remote = remoteSource(entry)
  return (
    <div className="card form import-form">
      <div className="import-row">
        <Field label="Nombre del repositorio" source="Del nombre de la carpeta">
          <input value={form.repoName} onChange={on('repoName')} required />
        </Field>
        <Field
          label="Rama por defecto"
          source={branchSource(r)}
          ok={r.defaultBranchSource === 'origin/HEAD'}
        >
          <input value={form.defaultBranch} onChange={on('defaultBranch')} className="mono" />
        </Field>
      </div>
      <Field label="Ruta en el runner" source="No se puede cambiar: es el repositorio que elegiste">
        <input value={r.path} readOnly className="mono" />
      </Field>
      <Field label="Remoto" source={remote.text} ok={remote.ok}>
        <input value={form.remoteUrl} onChange={on('remoteUrl')} className="mono" />
      </Field>
      <Field label="Comando de validación" source={validationSource(r)}>
        <input value={form.validationCommand} onChange={on('validationCommand')} className="mono" />
      </Field>
      <Field
        label="Informes JUnit (uno por línea)"
        source={r.testReportPaths.length ? `Los de ${toolLabel(r.tool)}` : 'Ninguno conocido'}
      >
        <textarea
          rows={2}
          value={form.testReportPaths}
          onChange={on('testReportPaths')}
          className="mono"
        />
      </Field>
      <Field
        label="Rutas protegidas (una por línea)"
        source={r.protectedPaths.length ? 'Existe .github/workflows' : 'Ninguna sugerida'}
      >
        <textarea
          rows={2}
          value={form.protectedPaths}
          onChange={on('protectedPaths')}
          className="mono"
        />
      </Field>
      {r.dirty && (
        <p className="import-note is-warn">
          <AlertTriangle size={16} aria-hidden="true" />
          <span>
            Hay cambios sin commitear. Los agentes trabajan en worktrees desde la rama por defecto,
            así que no los verán.
          </span>
        </p>
      )}
      <p className="import-note">
        <ShieldCheck size={16} aria-hidden="true" />
        <span>
          La política de los agentes hereda la global. Se cambia después en el repositorio.
        </span>
      </p>
    </div>
  )
}

function SummaryStep({
  path,
  form,
  learn,
  setLearn,
  onEdit,
}: {
  path: string
  form: ImportForm
  learn: boolean
  setLearn: (on: boolean) => void
  onEdit: (step: number) => void
}) {
  const request = importRequest(path, form)
  const r = request.repository
  return (
    <>
      <div className="card import-summary">
        <div className="import-summary-head">
          <h2>Proyecto</h2>
          <Button variant="link" onClick={() => onEdit(2)}>
            Cambiar
          </Button>
        </div>
        <dl>
          <dt>Clave</dt>
          <dd className="mono">{request.key}</dd>
          <dt>Nombre</dt>
          <dd>{request.name}</dd>
          <dt>Descripción</dt>
          <dd>{request.description ?? '—'}</dd>
        </dl>
      </div>
      <div className="card import-summary">
        <div className="import-summary-head">
          <h2>Repositorio</h2>
          <Button variant="link" onClick={() => onEdit(3)}>
            Cambiar
          </Button>
        </div>
        <dl>
          <dt>Nombre</dt>
          <dd>{r.name}</dd>
          <dt>Ruta</dt>
          <dd className="mono">{r.localPath}</dd>
          <dt>Remoto</dt>
          <dd className="mono">{r.remoteUrl ?? '—'}</dd>
          <dt>Rama</dt>
          <dd className="mono">{r.defaultBranch ?? 'main'}</dd>
          <dt>Validación</dt>
          <dd className="mono">{r.validationCommand ?? 'Sin verificación'}</dd>
          <dt>Rutas protegidas</dt>
          <dd className="mono">{r.protectedPaths?.length ? r.protectedPaths.join(', ') : '—'}</dd>
        </dl>
      </div>
      <label className="import-check">
        <input type="checkbox" checked={learn} onChange={(e) => setLearn(e.target.checked)} />
        <span>
          Lanzar «Conocer el repositorio» al terminar
          <span className="muted small">
            Genera docs/skynet/mapa-{request.key.toLowerCase()}.md para que los agentes entiendan el
            código. Es una ejecución de agentes, con su coste.
          </span>
        </span>
      </label>
    </>
  )
}

function Aside({ step, entry }: { step: number; entry: CatalogEntry | null }) {
  const items = useMemo((): { icon: ReactNode; text: ReactNode }[] => {
    const r = entry?.repository
    if (step === 3 && r) {
      return [
        r.lastCommit && {
          icon: <GitCommitHorizontal size={16} />,
          text: (
            <>
              Último commit <code>{r.lastCommit.slice(0, 7)}</code> «{r.lastCommitSubject}»
            </>
          ),
        },
        {
          icon: r.dirty ? <AlertTriangle size={16} /> : <Check size={16} />,
          text: r.dirty ? 'Hay cambios sin commitear' : 'Sin cambios sin commitear',
        },
        r.agentInstructions && {
          icon: <FileText size={16} />,
          text: 'Tiene CLAUDE.md o AGENTS.md',
        },
        { icon: <Package size={16} />, text: toolLabel(r.tool) ?? 'Herramienta no reconocida' },
      ].filter(Boolean) as { icon: ReactNode; text: ReactNode }[]
    }
    if (step === 2) {
      return [
        {
          icon: <FileText size={16} />,
          text: 'Nombre: el manifiesto del proyecto o, si no hay, la carpeta.',
        },
        {
          icon: <FileText size={16} />,
          text: 'Descripción: el manifiesto, el README o GitHub, en ese orden.',
        },
        {
          icon: <Info size={16} />,
          text: 'Clave: el nombre si cabe en 6 letras; si no, sus 3 primeras. Siempre la puedes cambiar.',
        },
      ]
    }
    if (step === 4) {
      return [
        {
          icon: <Check size={16} />,
          text: 'Proyecto y repositorio se crean a la vez: si algo falla, no se crea nada.',
        },
        { icon: <Info size={16} />, text: 'Queda en Actividad como importado desde su carpeta.' },
        { icon: <GitBranch size={16} />, text: 'Más repositorios, después desde el proyecto.' },
      ]
    }
    return [
      {
        icon: <HardDrive size={16} />,
        text: 'El runner busca repositorios git en las carpetas de SKYNET_REPO_ROOTS.',
      },
      { icon: <FileText size={16} />, text: 'Lee el remoto, la rama, el manifiesto y el README.' },
      {
        icon: <ShieldCheck size={16} />,
        text: 'No lee .env ni secretos, y quita las credenciales de las URL.',
      },
      { icon: <Check size={16} />, text: 'Nada se crea hasta el último paso.' },
    ]
  }, [step, entry])
  const title =
    step === 2
      ? 'De dónde sale cada dato'
      : step === 3
        ? 'Lo que vio el runner'
        : step === 4
          ? 'Al crear'
          : 'Qué hace este asistente'
  return (
    <aside className="card import-aside" aria-label={title}>
      <h2>{title}</h2>
      <ul>
        {items.map((item, i) => (
          <li key={i}>
            <span aria-hidden="true">{item.icon}</span>
            <span>{item.text}</span>
          </li>
        ))}
      </ul>
    </aside>
  )
}
