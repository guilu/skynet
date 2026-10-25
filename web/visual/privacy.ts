import { homedir, hostname, userInfo } from 'node:os'

/**
 * Escaneo de privacidad de la galería: lo que se pinta en las capturas (y lo que lleva la API, el
 * índice y el mapa) no puede llevar secretos, correos, rutas personales ni datos de la máquina.
 */

export interface Finding {
  where: string
  rule: string
  match: string
}

/** Rutas sintéticas que sí pueden salir: el entorno aislado y la de los fixtures de fake-claude. */
const ALLOWED_PATHS = ['/tmp/skynet-visual', '/home/dev/']
/** Dominios reservados para ejemplos (RFC 2606): los correos de los commits de juguete. */
const ALLOWED_MAIL = /@example\.(invalid|com|org)$/i
/** Nombres de usuario y de máquina demasiado genéricos para buscarlos como texto. */
const GENERIC = new Set(['root', 'runner', 'user', 'admin', 'ubuntu', 'node', 'dev', 'localhost'])

const RULES: { rule: string; re: RegExp }[] = [
  { rule: 'correo', re: /[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}/g },
  { rule: 'token de GitHub', re: /\b(gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})/g },
  { rule: 'clave de AWS', re: /\b(AKIA|ASIA)[0-9A-Z]{16}\b/g },
  { rule: 'clave de API', re: /\bsk-(ant-|proj-)?[A-Za-z0-9_-]{20,}/g },
  { rule: 'token de Slack', re: /\bxox[abprs]-[A-Za-z0-9-]{10,}/g },
  { rule: 'clave privada', re: /-----BEGIN [A-Z ]*PRIVATE KEY-----/g },
  {
    rule: 'ruta personal',
    re: /(\/home\/[A-Za-z0-9._-]+\/?|\/Users\/[A-Za-z0-9._-]+|\/root\/|[A-Za-z]:\\Users\\[^\\\s]+)/g,
  },
]

const escape = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/** Lo propio de esta máquina y de esta ejecución que tampoco debe salir. */
export function machineSecrets(env: NodeJS.ProcessEnv = process.env): string[] {
  const values = [
    env.SKYNET_ADMIN_PASSWORD,
    env.SKYNET_RUNNER_REGISTRATION_TOKEN,
    homedir(),
    hostname(),
    userInfo().username,
  ]
  return values.filter(
    (v): v is string => !!v && v.length >= 4 && v !== '/' && !GENERIC.has(v.toLowerCase()),
  )
}

/** Hallazgos en un texto; `where` dice de dónde sale (captura, fichero…). */
export function scanText(where: string, text: string, secrets: string[] = []): Finding[] {
  const found: Finding[] = []
  for (const { rule, re } of RULES) {
    for (const m of text.matchAll(re)) {
      const match = m[0]
      if (rule === 'correo' && ALLOWED_MAIL.test(match)) continue
      if (rule === 'ruta personal' && ALLOWED_PATHS.some((p) => text.startsWith(p, m.index)))
        continue
      found.push({ where, rule, match })
    }
  }
  for (const s of secrets) {
    const re = new RegExp(`(^|[^A-Za-z0-9])${escape(s)}($|[^A-Za-z0-9])`, 'i')
    if (re.test(text)) found.push({ where, rule: 'dato de esta máquina', match: s })
  }
  return found
}
