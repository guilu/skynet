import { useQuery } from '@tanstack/react-query'
import { GraduationCap } from 'lucide-react'
import { useSearchParams } from 'react-router'
import { api, type Lesson, type LessonStatus } from '../api'
import { ErrorMessage } from '../components/ErrorMessage'
import { LessonCard } from '../components/lessons/LessonCard'
import { lessonsKey } from '../components/lessons/lessonFormat'
import { EmptyState } from '../components/list/DataTable'
import { LinesSkeleton } from '../components/ui/Skeleton'
import { TabList, TabPanel } from '../components/ui/Tabs'

const TABS: { id: LessonStatus; label: string }[] = [
  { id: 'propuesta', label: 'Propuestas' },
  { id: 'aplicada', label: 'Aplicadas' },
  { id: 'descartada', label: 'Descartadas' },
]

const EMPTY: Record<LessonStatus, string> = {
  propuesta: 'Ninguna lección propuesta.',
  aplicada: 'Ninguna lección aplicada.',
  descartada: 'Ninguna lección descartada.',
}

/**
 * Fábrica › Lecciones (AP-C): lo que se repite en los hallazgos de cada repositorio, propuesto
 * como regla (AP-B). Las propuestas se aplican (en la memoria o en los criterios del juez, según
 * el tipo) o se descartan; las aplicadas y las descartadas van en sus pestañas, con quién y
 * cuándo. La pestaña va en la URL (`?status=`).
 */
export function LessonsPage() {
  const [params, setParams] = useSearchParams()
  const asked = params.get('status')
  const status: LessonStatus = TABS.some((t) => t.id === asked)
    ? (asked as LessonStatus)
    : 'propuesta'
  const lessons = useQuery({
    queryKey: [...lessonsKey, 'factory', status],
    queryFn: () => api.lessons(status),
  })
  const groups = byRepository(lessons.data ?? [])
  return (
    <>
      <h1>Lecciones</h1>
      <p className="muted">
        Cuando los jueces piden lo mismo en dos ejecuciones distintas de un repositorio, o señalan
        algo que se da por bueno, Skynet propone una regla. No se aplica nada sin ti.
      </p>
      <TabList
        label="Estado de las lecciones"
        tabs={TABS}
        selected={status}
        onSelect={(next) =>
          setParams(next === 'propuesta' ? {} : { status: next }, { replace: true })
        }
        idPrefix="lessons-tab"
        panelId="lessons-panel"
      />
      <TabPanel id="lessons-panel" labelledBy={`lessons-tab-${status}`} className="tab-body">
        <ErrorMessage error={lessons.error} />
        {lessons.isPending && <LinesSkeleton label="las lecciones" />}
        {lessons.data && groups.length === 0 && (
          <EmptyState icon={GraduationCap} title={EMPTY[status]}>
            {status === 'propuesta' && (
              <p>Saldrán aquí cuando una clase de hallazgo se repita en dos ejecuciones.</p>
            )}
          </EmptyState>
        )}
        {groups.map(([key, list]) => (
          <section key={key} className="lesson-group" aria-label={key}>
            <h2>{key}</h2>
            {list.map((l) => (
              <LessonCard key={`${l.id}-${l.status}`} lesson={l} showRepository={false} />
            ))}
          </section>
        ))}
      </TabPanel>
    </>
  )
}

/** Las lecciones por «proyecto › repositorio», en el orden en que llegan. */
function byRepository(lessons: Lesson[]): [string, Lesson[]][] {
  const groups = new Map<string, Lesson[]>()
  for (const l of lessons) {
    const key = `${l.projectName} › ${l.repositoryName}`
    groups.set(key, [...(groups.get(key) ?? []), l])
  }
  return [...groups.entries()]
}
