import { useMutation } from '@tanstack/react-query'
import { Eye, EyeOff } from 'lucide-react'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { api, type Session } from '../api'
import { ErrorMessage } from '../components/ErrorMessage'
import { LoginSkyny } from '../components/companion/LoginSkyny'
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
  const login = useMutation({
    mutationFn: () => api.login(username.trim(), password),
    onSuccess: onLogin,
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
            <Button type="submit" disabled={login.isPending}>
              {login.isPending ? 'Entrando…' : 'Entrar'}
            </Button>
            <ErrorMessage error={login.error} />
          </form>
        </section>
      </div>
    </main>
  )
}
