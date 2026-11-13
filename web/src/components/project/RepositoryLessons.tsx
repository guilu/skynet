import { useQuery } from '@tanstack/react-query'
import { GraduationCap } from 'lucide-react'
import { Link } from 'react-router'
import { api, type Repository } from '../../api'
import { ErrorMessage } from '../ErrorMessage'
import { LessonCard } from '../lessons/LessonCard'
import { lessonsKey } from '../lessons/lessonFormat'

/**
 * En la ficha de un repositorio (AP-C): sus lecciones aplicadas, que se pueden desactivar. Las
 * propuestas están en Fábrica › Lecciones.
 */
export function RepositoryLessons({ repository: r }: { repository: Repository }) {
  const lessons = useQuery({
    queryKey: [...lessonsKey, 'project', r.projectId, 'aplicada'],
    queryFn: () => api.projectLessons(r.projectId, 'aplicada'),
  })
  const applied = (lessons.data ?? []).filter((l) => l.repositoryId === r.id)
  return (
    <section className="repo-section" aria-label={`Lecciones de ${r.name}`}>
      <div className="repo-section-head">
        <h3>
          <GraduationCap size={18} strokeWidth={2.5} aria-hidden="true" /> Lecciones aplicadas
        </h3>
        <Link className="small" to="/lessons">
          Ver las propuestas
        </Link>
      </div>
      <ErrorMessage error={lessons.error} />
      {lessons.data && applied.length === 0 && (
        <p className="muted small">
          Ninguna: lo que se repite en sus hallazgos se propone en Fábrica › Lecciones.
        </p>
      )}
      {applied.map((l) => (
        <LessonCard key={`${l.id}-${l.status}`} lesson={l} showRepository={false} />
      ))}
    </section>
  )
}
