import { describe, expect, it } from 'vitest'
import providers from '../../../fixtures/contracts/providers.json'
import type { ProviderKind } from '../api'
import { unsupportedReason } from './providers'

const kinds = providers as ProviderKind[]

describe('unsupportedReason', () => {
  it('explica lo que un proveedor no admite', () => {
    expect(unsupportedReason(kinds, 'gemini', 'FORK')).toBe(
      'Gemini CLI no permite bifurcar una sesión',
    )
    expect(unsupportedReason(kinds, 'gemini', 'RESUME')).toBeNull()
    expect(unsupportedReason(kinds, 'codex', 'FORK')).toBeNull()
  })

  it('no bloquea lo que no conoce', () => {
    expect(unsupportedReason(undefined, 'gemini', 'FORK')).toBeNull()
    expect(unsupportedReason(kinds, 'command', 'FORK')).toBeNull()
  })
})
