import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { StatusBadge } from './StatusBadge'

describe('StatusBadge', () => {
  it('lleva el icono del estado y su texto, sin Skyny', () => {
    for (const status of ['EXECUTING', 'SUCCEEDED', 'FAILED', 'OPEN']) {
      const { container, unmount } = render(<StatusBadge status={status} />)
      expect(container.querySelector('.pill-icon')).not.toBeNull()
      expect(container.querySelector('svg.companion')).toBeNull()
      unmount()
    }
  })
})
