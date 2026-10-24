import { X } from 'lucide-react'
import { useEffect } from 'react'
import { Link } from 'react-router'
import { Companion } from './Companion'
import { useCompanion } from './context'
import { NOTICE_FACE, TOAST_MS, toastText, type Toast } from './notices'

/**
 * Los avisos flotantes: abajo a la derecha (arriba en el móvil), como mucho tres. La zona es
 * `aria-live` (sin `role="status"`, que ya usan los mensajes de las páginas) y siempre está.
 */
export function Toasts() {
  const { toasts, dismissToast } = useCompanion()
  return (
    <div className="toasts" aria-live="polite">
      {toasts.map((t) => (
        <ToastCard key={t.id} toast={t} onClose={() => dismissToast(t.id)} />
      ))}
    </div>
  )
}

function ToastCard({ toast, onClose }: { toast: Toast; onClose: () => void }) {
  const ms = TOAST_MS[toast.kind]
  // Cada aviso nuevo agrupado en la tarjeta reinicia la cuenta.
  useEffect(() => {
    if (ms == null) return
    const t = window.setTimeout(onClose, ms)
    return () => window.clearTimeout(t)
    // onClose cambia en cada render; lo que cuenta es el último aviso (`at`).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ms, toast.at])
  const { title, body, href, action } = toastText(toast)
  return (
    <div className="toast" data-kind={toast.kind}>
      <Companion state={NOTICE_FACE[toast.kind]} size={44} />
      <div className="toast-text">
        <strong>{title}</strong>
        {body && <span>{body}</span>}
        <Link to={href} className="toast-action" onClick={onClose}>
          {action}
        </Link>
      </div>
      <button type="button" className="toast-close" aria-label="Cerrar el aviso" onClick={onClose}>
        <X size={16} strokeWidth={2.75} aria-hidden="true" />
      </button>
    </div>
  )
}
