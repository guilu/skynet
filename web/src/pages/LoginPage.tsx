import { useMutation } from '@tanstack/react-query'
import { Eye, EyeOff } from 'lucide-react'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { api, type Session } from '../api'
import { ErrorMessage } from '../components/ErrorMessage'
import { LEAVE_MS, LoginSkyny } from '../components/companion/LoginSkyny'
import { can3D } from '../components/companion/look'
import { Button } from '../components/ui/Button'

/**
 * Entrada a la web con el usuario único del control plane. Una columna centrada, sin recuadro:
 * Skyny grande, «Entra en Skynet» y el formulario. Skyny (en 3D desde TD-A) saluda y va contando
 * cosas en su pantalla, sigue con los ojos lo que haces, se tapa los ojos mientras escribes la
 * contraseña y mira de reojo si la muestras (SY-C). Se puede tocar y girar.
 */
export function LoginPage({ onLogin }: { onLogin: (session: Session) => void }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [secret, setSecret] = useState(false)
  const [reveal, setReveal] = useState(false)
  const heading = useRef<HTMLHeadingElement>(null)
  const [failures, setFailures] = useState(0)
  // Bien: Skyny sale volando y la web entra cuando se ha ido (sin 3D, al momento).
  const [leaving, setLeaving] = useState(false)
  const login = useMutation({
    mutationFn: () => api.login(username.trim(), password),
    onSuccess: (session) => {
      if (!can3D()) return onLogin(session)
      setLeaving(true)
      window.setTimeout(() => onLogin(session), LEAVE_MS)
    },
    onError: () => setFailures((n) => n + 1),
  })

  useEffect(() => {
    heading.current?.focus()
  }, [])

  const submit = (e: FormEvent) => {
    e.preventDefault()
    login.mutate()
  }

  return (
    <main className="login">
      <div className="login-card">
        <section className="login-hero" aria-hidden="true">
          <LoginSkyny
            pose={reveal ? 'peek' : secret ? 'cover' : undefined}
            typingPassword={secret && !reveal}
            failures={failures}
            leaving={leaving}
          />
        </section>
        <section className="login-form">
          <h1 ref={heading} tabIndex={-1}>
            Entra en Skynet
          </h1>
          <form className="form" onSubmit={submit}>
            <label>
              Usuario
              <input
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                autoComplete="username"
                required
              />
            </label>
            <div className="login-password">
              <label>
                Contraseña
                <input
                  type={reveal ? 'text' : 'password'}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  onFocus={() => setSecret(true)}
                  onBlur={() => setSecret(false)}
                  autoComplete="current-password"
                  required
                />
              </label>
              <button
                type="button"
                className="login-reveal"
                aria-label="Mostrar la clave"
                aria-pressed={reveal}
                onClick={() => setReveal((r) => !r)}
              >
                {reveal ? (
                  <EyeOff size={18} strokeWidth={2.25} aria-hidden="true" />
                ) : (
                  <Eye size={18} strokeWidth={2.25} aria-hidden="true" />
                )}
              </button>
            </div>
            <Button type="submit" disabled={login.isPending || leaving}>
              {login.isPending || leaving ? 'Entrando…' : 'Entrar'}
            </Button>
            <ErrorMessage error={login.error} />
          </form>
        </section>
      </div>
    </main>
  )
}
