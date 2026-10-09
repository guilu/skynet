import * as Dialog from '@radix-ui/react-dialog'
import { X } from 'lucide-react'
import type { ReactNode } from 'react'

/**
 * Panel lateral para los formularios: entra por la derecha y, en el móvil, sube desde abajo. Es un
 * diálogo modal de Radix, así que guarda el foco dentro, se cierra con Escape y lo devuelve al
 * botón que lo abrió.
 */
export function Sheet({
  open,
  onOpenChange,
  title,
  description,
  children,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description?: ReactNode
  children: ReactNode
}) {
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="sheet-overlay" />
        <Dialog.Content
          className="sheet"
          {...(description ? {} : { 'aria-describedby': undefined })}
        >
          <div className="sheet-head">
            <Dialog.Title className="sheet-title">{title}</Dialog.Title>
            <Dialog.Close className="icon-btn" aria-label="Cerrar">
              <X size={22} strokeWidth={2.5} aria-hidden="true" />
            </Dialog.Close>
          </div>
          {description && (
            <Dialog.Description className="sheet-description">{description}</Dialog.Description>
          )}
          <div className="sheet-body">{children}</div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
