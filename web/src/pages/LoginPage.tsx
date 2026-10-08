import { useMutation } from '@tanstack/react-query'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { api, type Session } from '../api'
import { ErrorMessage } from '../components/ErrorMessage'
import { Button } from '../components/ui/Button'

/** Entrada a la web con el usuario único del control plane. */
export function LoginPage({ onLogin }: { onLogin: (session: Session) => void }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const heading = useRef<HTMLHeadingElement>(null)
  const login = useMutation({
    mutationFn: () => api.login(username.trim(), password),
    onSuccess: onLogin,
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
      <h1 ref={heading} tabIndex={-1}>
        Skynet
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
        <label>
          Contraseña
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            required
          />
        </label>
        <Button type="submit" disabled={login.isPending}>
          {login.isPending ? 'Entrando…' : 'Entrar'}
        </Button>
        <ErrorMessage error={login.error} />
      </form>
    </main>
  )
}
