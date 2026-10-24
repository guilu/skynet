import { MEMORY_KINDS, type MemoryKind, type MemoryOrigin, type MemoryVia } from '../../api'

const KIND_LABEL = Object.fromEntries(MEMORY_KINDS.map((k) => [k.value, k.label])) as Record<
  MemoryKind,
  string
>

export const kindLabel = (kind: MemoryKind) => KIND_LABEL[kind] ?? kind

export const ORIGIN_LABEL: Record<MemoryOrigin, string> = {
  AGENT: 'un agente',
  HUMAN: 'una persona',
  SYSTEM: 'Skynet',
}

/** Qué hizo una invocación con una observación, para el apartado «Memoria» del agente. */
export const VIA_LABEL: Record<MemoryVia, string> = {
  PROMPT: 'en el bloque del prompt',
  CONTEXT: 'con mem_context',
  SEARCH: 'en una búsqueda',
  GET: 'con mem_get',
  SAVE: 'con mem_save',
}

/** Los tipos que puede poner una persona al editar: la bitácora solo la escribe Skynet. */
export const EDITABLE_KINDS = MEMORY_KINDS.filter((k) => k.value !== 'RUN_LOG')

const day = new Intl.DateTimeFormat('es-ES', { dateStyle: 'long' })

/** Día de una entrada de la línea de tiempo, en la zona del navegador. */
export const dayLabel = (iso: string) => day.format(new Date(iso))
