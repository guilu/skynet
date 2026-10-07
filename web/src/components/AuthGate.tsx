import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, type ReactNode } from 'react'
import { api, ApiError, SESSION_KEY, UNAUTHORIZED_EVENT, type Session } from '../api'
import { LoginPage } from '../pages/LoginPage'
import { ErrorMessage } from './ErrorMessage'

/**
 * Muestra el login mientras no haya sesión. Si la API responde 401 a mitad (sesión caducada o
 * cerrada en otra pestaña), vuelve al login sin cambiar de página: tras entrar, se sigue donde se
 * estaba.
 */
export function AuthGate({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  // null: la sesión se cerró o caducó después de cargar la página.
  const session = useQuery<Session | null>({
    queryKey: SESSION_KEY,
    queryFn: api.session,
    retry: false,
    staleTime: Infinity,
  })

  useEffect(() => {
    const expired = () => queryClient.setQueryData(SESSION_KEY, null)
    window.addEventListener(UNAUTHORIZED_EVENT, expired)
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, expired)
  }, [queryClient])

  const unauthorized = session.error instanceof ApiError && session.error.status === 401
  if (unauthorized || session.data === null) {
    return (
      <LoginPage
        onLogin={(user) => {
          // Lo cargado con la sesión anterior se vuelve a pedir con la nueva.
          queryClient.removeQueries({ predicate: (q) => q.queryKey[0] !== SESSION_KEY[0] })
          queryClient.setQueryData(SESSION_KEY, user)
        }}
      />
    )
  }
  if (session.error) {
    return (
      <main className="login">
        <ErrorMessage error={session.error} />
      </main>
    )
  }
  if (!session.data) return <p className="muted login">Cargando…</p>
  return <>{children}</>
}
