import { useQuery } from '@tanstack/react-query'
import { api, type ProviderCapability, type ProviderKind } from '../api'
import { providerName } from '../format'

/** Los proveedores que Skynet sabe ejecutar, con lo que admite cada uno. */
export function useProviders() {
  return useQuery({ queryKey: ['providers'], queryFn: () => api.providers() })
}

/**
 * Por qué el proveedor `id` no admite `capability`, o null si la admite. Mientras no se sabe (o
 * si es un proveedor que Skynet no conoce, como los comandos), no se bloquea nada: el servidor
 * responde con el motivo.
 */
export function unsupportedReason(
  providers: ProviderKind[] | undefined,
  id: string,
  capability: ProviderCapability,
): string | null {
  const kind = providers?.find((p) => p.id === id)
  if (!kind || kind.capabilities.includes(capability)) return null
  return `${kind.displayName} no permite ${CAPABILITY_TEXT[capability]}`
}

const CAPABILITY_TEXT: Record<ProviderCapability, string> = {
  RESUME: 'continuar una sesión',
  FORK: 'bifurcar una sesión',
  STRUCTURED_OUTPUT: 'salida estructurada',
  MAX_TURNS: 'limitar los turnos',
}

/** «no lo admite Gemini CLI», para lo que la política efectiva ignora. */
export function ignoredBy(provider: string): string {
  return `no lo admite ${providerName(provider)}`
}
