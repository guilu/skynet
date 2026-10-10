import { FileCheck2, FilePen, FileWarning } from 'lucide-react'
import type { DefinitionStatus, VersionView } from '../../api'
import { Pill } from '../ui/Pill'

const STATUS_LABEL: Record<DefinitionStatus, string> = {
  DRAFT: 'Borrador con errores',
  VALIDATED: 'Borrador validado',
  PUBLISHED: 'Publicada',
}

/** Estado de una versión como píldora: «v3 · Publicada», «v4 · Borrador con errores». */
export function VersionPill({
  version,
  showVersion = true,
}: {
  version: Pick<VersionView, 'version' | 'status'>
  showVersion?: boolean
}) {
  const text = showVersion
    ? `v${version.version} · ${STATUS_LABEL[version.status]}`
    : STATUS_LABEL[version.status]
  switch (version.status) {
    case 'PUBLISHED':
      return (
        <Pill tone="ok" icon={FileCheck2}>
          {text}
        </Pill>
      )
    case 'VALIDATED':
      return (
        <Pill tone="neutral" icon={FilePen}>
          {text}
        </Pill>
      )
    case 'DRAFT':
      return (
        <Pill tone="warn" icon={FileWarning}>
          {text}
        </Pill>
      )
  }
}
