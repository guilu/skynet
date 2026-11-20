import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Runner } from '../../api'
import { shortVersion, simulatedProviders } from '../../format'
import { renderAt } from '../../test/render'
import { RunnerRow } from './RunnerRow'

const providers = [
  { id: 'claude-code', version: '2.1.0 (fake-claude)', simulated: true },
  { id: 'codex', version: 'codex-cli 0.163.0', simulated: false },
]

const runner: Runner = {
  id: 'r1',
  name: 'omarchy',
  capacity: 2,
  activeAgents: 1,
  runnerVersion: 'dev',
  providerVersion: null,
  registeredAt: '2026-11-20T07:00:00Z',
  lastHeartbeatAt: '2026-11-20T07:24:36Z',
  status: 'ONLINE',
  archivedAt: null,
  providers,
}

describe('RunnerRow', () => {
  it('pinta las versiones en chips y marca solo los CLIs simulados', () => {
    renderAt('/runners', <RunnerRow runner={runner} />)
    const versions = within(screen.getByRole('list', { name: 'Versiones' })).getAllByRole(
      'listitem',
    )
    expect(versions.map((v) => v.textContent)).toEqual([
      'runner dev',
      'Claude Code 2.1.0: simulado',
      'Codex 0.163.0',
    ])
    expect(versions[1]).toHaveClass('tag-warn')
    expect(versions[1]).toHaveAttribute('title', expect.stringContaining('2.1.0 (fake-claude)'))
    expect(versions[2]).not.toHaveClass('tag-warn')
    expect(screen.getByText('1 / 2')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Revocar token…' })).toBeInTheDocument()
  })

  it('se queda con el número de versión de lo que responde el CLI', () => {
    expect(shortVersion('codex-cli 0.50.0')).toBe('0.50.0')
    expect(shortVersion('2.1.319 (Claude Code)')).toBe('2.1.319')
    expect(shortVersion('dev')).toBe('dev')
  })

  it('lista los nombres de los proveedores simulados', () => {
    expect(simulatedProviders(providers)).toEqual(['Claude Code'])
    expect(simulatedProviders([providers[1]])).toEqual([])
  })
})
