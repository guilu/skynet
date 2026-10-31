import { execFileSync } from 'node:child_process'
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'
import { join } from 'node:path'

/**
 * GitHub falso para las E2E (GH-C): lo justo de la API de una GitHub App para que el control plane
 * compruebe la App, sincronice una instalación (`guilu`, con `guilu/demo-repo`) y revise las
 * entregas. No verifica el JWT (eso lo prueba `GitHubIT`) y nunca llama al GitHub real.
 *
 *   node e2e/fake-github.ts <puerto>
 *
 * `scripts/e2e.sh` lo arranca antes que el control plane y le pasa `SKYNET_GITHUB_API_URL`.
 *
 * Para la entrega por PR (EN-C, `delivery.e2e.ts`), el runner sube a un remoto local
 * (`SKYNET_GITHUB_GIT_URL=file://…`) y este GitHub lee de ahí el commit de cada rama: abre la PR,
 * da la CI de cualquier commit en verde (un check `test`), la fusiona solo con el `sha` de su
 * cabeza y borra la rama. Lo que pasa con cada PR se ve en `GET /_e2e/pulls`.
 */
export const INSTALLATION = 7
export const REPOSITORY = { id: 5001, full_name: 'guilu/demo-repo', private: true }

function json(res: ServerResponse, status: number, body: unknown) {
  res.writeHead(status, { 'Content-Type': 'application/json' })
  res.end(JSON.stringify(body))
}

/** Dónde están los remotos locales: `<dir>/<owner>/<repo>.git`. */
const remotes = process.env.E2E_GITHUB_REMOTES ?? ''

interface Pull {
  number: number
  html_url: string
  state: 'open' | 'closed'
  merged: boolean
  merge_commit_sha: string | null
  merge_method: string | null
  title: string
  body: string
  draft: boolean
  labels: string[]
  head: { ref: string; sha: string }
  base: { ref: string }
  comments: string[]
  deletedBranch: boolean
}

const pulls = new Map<string, Pull[]>()

/** El commit de la rama en el remoto local, o null si no existe. */
function branchSha(fullName: string, branch: string): string | null {
  try {
    return execFileSync(
      'git',
      ['--git-dir', join(remotes, `${fullName}.git`), 'rev-parse', `refs/heads/${branch}`],
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] },
    ).trim()
  } catch {
    return null
  }
}

/** La PR con la cabeza que tenga ahora su rama (mientras está abierta). */
function view(fullName: string, pr: Pull): Pull {
  if (pr.state === 'open') {
    pr.head = { ...pr.head, sha: branchSha(fullName, pr.head.ref) ?? pr.head.sha }
  }
  return pr
}

async function body(req: IncomingMessage): Promise<Record<string, unknown>> {
  const chunks: Buffer[] = []
  for await (const chunk of req) chunks.push(chunk as Buffer)
  const text = Buffer.concat(chunks).toString('utf8')
  return text ? JSON.parse(text) : {}
}

const REPO = /^\/repos\/([^/]+\/[^/]+)(\/.*)?$/

/** La API de un repositorio (EN-A, EN-B y EN-C): sus PR, la CI de un commit y la fusión. */
async function repos(req: IncomingMessage, res: ServerResponse, path: string, query: string) {
  const [, fullName, rest = ''] = path.match(REPO) ?? []
  const list = pulls.get(fullName) ?? []
  pulls.set(fullName, list)
  const find = (n: string) => list.find((p) => p.number === Number(n))
  let m: RegExpMatchArray | null
  if (req.method === 'GET' && rest === '') {
    return json(res, 200, { full_name: fullName, default_branch: 'main' })
  }
  if (req.method === 'GET' && rest === '/pulls') {
    const head = new URLSearchParams(query).get('head') ?? ''
    const owner = fullName.split('/')[0]
    return json(
      res,
      200,
      list
        .filter((p) => p.state === 'open' && `${owner}:${p.head.ref}` === head)
        .map((p) => view(fullName, p)),
    )
  }
  if (req.method === 'POST' && rest === '/pulls') {
    const request = await body(req)
    const branch = String(request.head)
    const number = 12 + list.length
    const pr: Pull = {
      number,
      html_url: `https://github.com/${fullName}/pull/${number}`,
      state: 'open',
      merged: false,
      merge_commit_sha: null,
      merge_method: null,
      title: String(request.title ?? ''),
      body: String(request.body ?? ''),
      draft: Boolean(request.draft),
      labels: [],
      head: { ref: branch, sha: branchSha(fullName, branch) ?? '0'.repeat(40) },
      base: { ref: String(request.base ?? 'main') },
      comments: [],
      deletedBranch: false,
    }
    list.push(pr)
    return json(res, 201, pr)
  }
  if ((m = rest.match(/^\/pulls\/(\d+)$/)) && find(m[1])) {
    const pr = find(m[1])!
    if (req.method === 'PATCH') {
      const request = await body(req)
      pr.title = String(request.title ?? pr.title)
      pr.body = String(request.body ?? pr.body)
    }
    return json(res, 200, view(fullName, pr))
  }
  if (req.method === 'PUT' && (m = rest.match(/^\/pulls\/(\d+)\/merge$/)) && find(m[1])) {
    const pr = view(fullName, find(m[1])!)
    const request = await body(req)
    if (pr.state !== 'open') return json(res, 405, { message: 'Pull Request is not mergeable' })
    if (request.sha !== pr.head.sha) {
      return json(res, 409, { message: 'Head branch was modified. Review and try again.' })
    }
    pr.state = 'closed'
    pr.merged = true
    pr.merge_commit_sha = 'e'.repeat(40)
    pr.merge_method = String(request.merge_method)
    return json(res, 200, {
      sha: pr.merge_commit_sha,
      merged: true,
      message: 'Pull Request merged',
    })
  }
  if (req.method === 'DELETE' && (m = rest.match(/^\/git\/refs\/heads\/(.+)$/))) {
    const branch = decodeURIComponent(m[1])
    execFileSync('git', [
      '--git-dir',
      join(remotes, `${fullName}.git`),
      'update-ref',
      '-d',
      `refs/heads/${branch}`,
    ])
    list.filter((p) => p.head.ref === branch).forEach((p) => (p.deletedBranch = true))
    res.writeHead(204)
    return res.end()
  }
  if (
    req.method === 'POST' &&
    (m = rest.match(/^\/issues\/(\d+)\/(labels|comments)$/)) &&
    find(m[1])
  ) {
    const pr = find(m[1])!
    const request = await body(req)
    if (m[2] === 'labels') {
      pr.labels.push(...((request.labels as string[]) ?? []))
      return json(
        res,
        200,
        pr.labels.map((name) => ({ name })),
      )
    }
    pr.comments.push(String(request.body))
    return json(res, 201, { id: pr.comments.length })
  }
  if (req.method === 'GET' && (m = rest.match(/^\/commits\/([0-9a-f]+)\/check-runs$/))) {
    const run = {
      id: 9001,
      name: 'test',
      status: 'completed',
      conclusion: 'success',
      html_url: `https://github.com/${fullName}/runs/9001`,
      app: { slug: 'github-actions' },
      output: { title: 'test: success' },
    }
    return json(res, 200, { total_count: 1, check_runs: [run] })
  }
  if (req.method === 'GET' && rest.match(/^\/commits\/([0-9a-f]+)\/status$/)) {
    return json(res, 200, { state: 'pending', statuses: [] })
  }
  return json(res, 404, { message: 'Not Found' })
}

async function handle(req: IncomingMessage, res: ServerResponse) {
  const [path, query = ''] = (req.url ?? '/').split('?')
  if (req.method === 'GET' && path === '/_e2e/pulls') {
    return json(res, 200, Object.fromEntries(pulls))
  }
  if (!req.headers.authorization?.startsWith('Bearer ')) {
    return json(res, 401, { message: 'Requires authentication' })
  }
  if (req.method === 'GET' && path === '/app') {
    return json(res, 200, {
      id: 1,
      slug: 'skynet-e2e',
      name: 'skynet-e2e',
      owner: { login: 'guilu' },
      html_url: 'https://github.com/apps/skynet-e2e',
    })
  }
  if (req.method === 'GET' && path === '/app/installations') {
    return json(res, 200, [
      {
        id: INSTALLATION,
        account: { login: 'guilu', type: 'User' },
        repository_selection: 'selected',
        suspended_at: null,
      },
    ])
  }
  if (req.method === 'POST' && path === `/app/installations/${INSTALLATION}/access_tokens`) {
    return json(res, 201, {
      token: 'ghs_e2eFakeInstallationToken000000000000',
      expires_at: new Date(Date.now() + 3_600_000).toISOString(),
    })
  }
  if (req.method === 'GET' && path === '/installation/repositories') {
    return json(res, 200, { total_count: 1, repositories: [REPOSITORY] })
  }
  if (req.method === 'GET' && path === '/app/hook/deliveries') {
    return json(res, 200, [])
  }
  if (REPO.test(path)) {
    return repos(req, res, path, query)
  }
  return json(res, 404, { message: 'Not Found' })
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const port = Number(process.argv[2] ?? 4200)
  createServer((req, res) => {
    handle(req, res).catch((error: unknown) => json(res, 500, { message: String(error) }))
  }).listen(port, '127.0.0.1', () => console.log(`GitHub falso en http://127.0.0.1:${port}`))
}
