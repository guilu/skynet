import { useEffect, useState } from 'react'

type Health = 'checking' | 'up' | 'down'

export default function App() {
  const [health, setHealth] = useState<Health>('checking')

  useEffect(() => {
    const controller = new AbortController()
    fetch('/actuator/health', { signal: controller.signal })
      .then((res) => (res.ok ? res.json() : Promise.reject(new Error(String(res.status)))))
      .then((body: { status?: string }) => setHealth(body.status === 'UP' ? 'up' : 'down'))
      .catch((err: unknown) => {
        if (!(err instanceof DOMException && err.name === 'AbortError')) setHealth('down')
      })
    return () => controller.abort()
  }, [])

  return (
    <main style={{ maxWidth: 720, margin: '0 auto', padding: '48px 16px' }}>
      <h1>Skynet</h1>
      <p style={{ color: 'var(--muted)' }}>
        Plano de control y observabilidad para flujos agénticos de desarrollo.
      </p>
      <p>
        Control plane:{' '}
        <strong
          data-testid="health"
          style={{
            color:
              health === 'up' ? 'var(--ok)' : health === 'down' ? 'var(--bad)' : 'var(--muted)',
          }}
        >
          {health === 'up' ? 'disponible' : health === 'down' ? 'no disponible' : 'comprobando…'}
        </strong>
      </p>
    </main>
  )
}
