import { useQuery } from '@tanstack/react-query'
import { api, type GitHubAccess, type WebhookDeliveryStatus } from '../../api'
import type { StatusTone } from '../../format'

export const GITHUB_KEY = ['github'] as const
export const DELIVERIES_KEY = ['github', 'deliveries'] as const

/** El estado de la GitHub App, compartido por Ajustes y las tarjetas de repositorio. */
export function useGitHub() {
  return useQuery({ queryKey: GITHUB_KEY, queryFn: api.gitHub, staleTime: 30_000 })
}

/**
 * Los permisos que pide la App: los de EN y DS. Actions: lectura llegó con EN-B; sin él, la CI
 * roja se corrige solo con las anotaciones.
 */
export const PERMISSIONS: { name: string; level: string; why: string }[] = [
  { name: 'Metadata', level: 'lectura', why: 'obligatorio' },
  { name: 'Contents', level: 'lectura y escritura', why: 'subir las ramas de los agentes' },
  { name: 'Pull requests', level: 'lectura y escritura', why: 'abrir y fusionar las PR' },
  { name: 'Checks', level: 'lectura', why: 'esperar la CI' },
  { name: 'Commit statuses', level: 'lectura', why: 'la CI que usa statuses' },
  { name: 'Actions', level: 'lectura', why: 'leer los logs de la CI que falla' },
  { name: 'Issues', level: 'lectura y escritura', why: 'leer etiquetas y comentar' },
]

export const EVENTS = [
  'Pull request',
  'Pull request review',
  'Check suite',
  'Check run',
  'Status',
  'Issues',
  'Issue comment',
]

export const ACCESS: Record<GitHubAccess, { tone: StatusTone; label: string }> = {
  CONNECTED: { tone: 'ok', label: 'Con acceso' },
  NOT_INSTALLED: { tone: 'warn', label: 'Sin acceso' },
  NOT_GITHUB: { tone: 'neutral', label: 'No es de GitHub' },
}

export const DELIVERY: Record<WebhookDeliveryStatus, { tone: StatusTone; label: string }> = {
  PENDING: { tone: 'active', label: 'Pendiente' },
  PROCESSED: { tone: 'ok', label: 'Procesada' },
  FAILED: { tone: 'bad', label: 'Fallida' },
  IGNORED: { tone: 'neutral', label: 'Ignorada' },
}
