import { Upload } from 'lucide-react'
import { useRef } from 'react'
import { Button } from '../ui/Button'

/** «Importar archivo…»: lee un `.yaml` del disco y entrega su texto. */
export function ImportButton({ onText }: { onText: (text: string) => void }) {
  const input = useRef<HTMLInputElement>(null)
  return (
    <>
      <Button variant="secondary" onClick={() => input.current?.click()}>
        <Upload size={18} strokeWidth={2.5} aria-hidden="true" />
        Importar archivo…
      </Button>
      <input
        ref={input}
        type="file"
        accept=".yaml,.yml,text/yaml,application/x-yaml"
        hidden
        aria-label="Archivo YAML del workflow"
        onChange={(e) => {
          const file = e.target.files?.[0]
          if (file) void file.text().then(onText)
          e.target.value = ''
        }}
      />
    </>
  )
}
