import { Fragment } from 'react'
import type { ProviderInfo } from '../api'
import { providerName } from '../format'
import { SimulatedPill } from './ui/SimulatedPill'

/** Los proveedores de un runner con la versión de su CLI y «Simulado» en los que no son reales. */
export function ProviderVersions({ providers }: { providers: ProviderInfo[] }) {
  return (
    <>
      {providers.map((p, i) => (
        <Fragment key={p.id}>
          {i > 0 && ' · '}
          {p.version ? `${providerName(p.id)} ${p.version}` : providerName(p.id)}
          {p.simulated && (
            <>
              {' '}
              <SimulatedPill />
            </>
          )}
        </Fragment>
      ))}
    </>
  )
}
