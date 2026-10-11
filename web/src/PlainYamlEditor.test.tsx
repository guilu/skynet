import { fireEvent, render, renderHook, screen } from '@testing-library/react'
import { createRef } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import PlainYamlEditor from './components/workflow/PlainYamlEditor'
import { offsetOf } from './components/workflow/textPosition'
import type { YamlEditorHandle } from './components/workflow/YamlEditor'
import { useTouchOnly } from './useTouchOnly'

const YAML = 'id: a1\nstages:\n  - id: a\n    type: agent\n'

describe('editor de YAML en móviles', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('es un campo de texto normal, sin correcciones del teclado', () => {
    const onChange = vi.fn()
    const onSave = vi.fn()
    render(<PlainYamlEditor value={YAML} onChange={onChange} onSave={onSave} label="YAML" />)
    const area = screen.getByRole('textbox', { name: 'YAML' }) as HTMLTextAreaElement
    expect(area.value).toBe(YAML)
    expect(area).toHaveAttribute('autocapitalize', 'off')
    expect(area).toHaveAttribute('autocorrect', 'off')
    expect(area).toHaveAttribute('spellcheck', 'false')

    fireEvent.change(area, { target: { value: 'id: b2\n' } })
    expect(onChange).toHaveBeenCalledWith('id: b2\n')
    fireEvent.keyDown(area, { key: 's', ctrlKey: true })
    expect(onSave).toHaveBeenCalledOnce()
  })

  it('lleva el cursor a la línea y la columna de un problema', () => {
    const ref = createRef<YamlEditorHandle>()
    render(<PlainYamlEditor ref={ref} value={YAML} label="YAML" />)
    ref.current!.reveal(4, 11)
    const area = screen.getByRole('textbox') as HTMLTextAreaElement
    expect(document.activeElement).toBe(area)
    expect(area.selectionStart).toBe(YAML.indexOf('agent'))
  })

  it('calcula posiciones sin salirse de la línea ni del texto', () => {
    expect(offsetOf(YAML, 1, 1)).toBe(0)
    expect(offsetOf(YAML, 2, 1)).toBe(7)
    expect(offsetOf(YAML, 1, 99)).toBe(6)
    expect(offsetOf(YAML, 99, 1)).toBe(YAML.length)
  })

  it('se usa solo cuando el único puntero es un dedo', () => {
    const stub = (coarse: boolean, fine: boolean) =>
      vi.stubGlobal(
        'matchMedia',
        (query: string) =>
          ({
            matches: query === '(pointer: coarse)' ? coarse : fine,
            addEventListener: () => {},
            removeEventListener: () => {},
          }) as unknown as MediaQueryList,
      )
    stub(true, false)
    expect(renderHook(() => useTouchOnly()).result.current).toBe(true)
    stub(true, true) // tableta con trackpad
    expect(renderHook(() => useTouchOnly()).result.current).toBe(false)
    stub(false, true)
    expect(renderHook(() => useTouchOnly()).result.current).toBe(false)
  })
})
