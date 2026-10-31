import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'

/**
 * GitHub falso para las E2E (GH-C): lo justo de la API de una GitHub App para que el control plane
 * compruebe la App, sincronice una instalación (`guilu`, con `guilu/demo-repo`) y revise las
 * entregas. No verifica el JWT (eso lo prueba `GitHubIT`) y nunca llama al GitHub real.
 *
 *   node e2e/fake-github.ts <puerto>
 *
 * `scripts/e2e.sh` lo arranca antes que el control plane y le pasa `SKYNET_GITHUB_API_URL`.
 */
export const INSTALLATION = 7
export const REPOSITORY = { id: 5001, full_name: 'guilu/demo-repo', private: true }

function json(res: ServerResponse, status: number, body: unknown) {
  res.writeHead(status, { 'Content-Type': 'application/json' })
  res.end(JSON.stringify(body))
}

function handle(req: IncomingMessage, res: ServerResponse) {
  const path = (req.url ?? '/').replace(/\?.*/, '')
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
  return json(res, 404, { message: 'Not Found' })
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const port = Number(process.argv[2] ?? 4200)
  createServer(handle).listen(port, '127.0.0.1', () =>
    console.log(`GitHub falso en http://127.0.0.1:${port}`),
  )
}
