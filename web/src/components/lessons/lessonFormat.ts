import type { Lesson } from '../../api'

/** El tipo de una lección, como se lee. */
export const KIND_LABEL: Record<Lesson['kind'], string> = {
  prevenir: 'Prevenir',
  'no-senalar': 'No señalar',
}

/** Todas las consultas de lecciones, para refrescarlas a la vez tras decidir. */
export const lessonsKey = ['lessons'] as const
