import type { ChatAction, ChatActionKind, ChatConversation, ChatThread } from '../../api'

/** De qué va una conversación nueva según la página: el proyecto o el trabajo que se está viendo. */
export interface ChatContext {
  projectId?: string
  workItemId?: string
}

const PROJECT = /^\/projects\/([0-9a-f-]{36})(?:\/|$)/
const WORK_ITEM = /^\/work-items\/([0-9a-f-]{36})(?:\/|$)/

export function chatContext(pathname: string): ChatContext {
  const workItem = WORK_ITEM.exec(pathname)
  if (workItem) return { workItemId: workItem[1] }
  const project = PROJECT.exec(pathname)
  if (project) return { projectId: project[1] }
  return {}
}

export interface Suggestion {
  icon: string
  text: string
}

/** Tres sugerencias para empezar, según la página. */
export function suggestions(pathname: string): Suggestion[] {
  const context = chatContext(pathname)
  if (context.workItemId) {
    return [
      { icon: '⌕', text: '¿En qué punto está este trabajo?' },
      { icon: '▶', text: '¿Qué workflow le lanzarías a este trabajo?' },
      { icon: '☰', text: '¿Qué sabemos en la memoria que le afecte?' },
    ]
  }
  if (context.projectId) {
    return [
      { icon: '⌕', text: '¿Qué hay en marcha en este proyecto?' },
      { icon: '☰', text: '¿Qué decisiones recientes hay en su memoria?' },
      { icon: '!', text: '¿Qué ha fallado esta semana en este proyecto?' },
    ]
  }
  if (pathname.startsWith('/runs/')) {
    return [
      { icon: '⌕', text: '¿Cómo va esta ejecución?' },
      { icon: '!', text: '¿Por qué ha fallado?' },
      { icon: '☀', text: '¿Qué me espera hoy?' },
    ]
  }
  return [
    { icon: '☀', text: '¿Qué me espera hoy?' },
    { icon: '⌕', text: '¿Qué hay en marcha ahora mismo?' },
    { icon: '!', text: '¿Hay algo esperando mi aprobación?' },
  ]
}

/** «haiku» → «Haiku». */
export function modelName(model: string | undefined): string {
  if (!model) return 'Haiku'
  return model.charAt(0).toUpperCase() + model.slice(1)
}

/** Lo que dice Skyny debajo de su cara, con conversación. */
export function chatStatus(
  conversation: ChatConversation | undefined,
  actions: ChatAction[] | undefined,
  sending: boolean,
): string {
  if (sending || conversation?.state === 'ANSWERING') return 'Pensando…'
  if (conversation?.state === 'OFFLINE') return 'Ningún runner me atiende ahora'
  if (actions?.some((a) => a.status === 'PENDING')) return 'Esperando tu confirmación'
  return 'En reposo'
}

/** Si algún mensaje trae una acción, hace falta saber cómo está cada una. */
export function hasActions(thread: ChatThread | undefined): boolean {
  return !!thread?.messages.some((m) => m.cards.some((c) => c.kind === 'action'))
}

/** La pregunta de la tarjeta de una acción pendiente. */
export const ACTION_QUESTION: Record<ChatActionKind, string> = {
  LAUNCH: '¿Lo lanzo?',
  CREATE_WORK_ITEM: '¿Creo el trabajo?',
  CANCEL_RUN: '¿Cancelo la ejecución?',
  RETRY_STAGE: '¿Reintento la fase?',
  DECIDE_APPROVAL: '¿Decido la aprobación?',
  SAVE_MEMORY: '¿Lo guardo en la memoria?',
}

/** El botón que confirma cada acción. */
export const ACTION_CONFIRM: Record<ChatActionKind, string> = {
  LAUNCH: 'Confirmar y lanzar',
  CREATE_WORK_ITEM: 'Crear el trabajo',
  CANCEL_RUN: 'Cancelar la ejecución',
  RETRY_STAGE: 'Reintentar',
  DECIDE_APPROVAL: 'Confirmar la decisión',
  SAVE_MEMORY: 'Guardar',
}

/** Las acciones que no se deshacen piden el botón de peligro. */
export const DANGEROUS: ReadonlySet<ChatActionKind> = new Set(['CANCEL_RUN'])
