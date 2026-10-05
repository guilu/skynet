import { defineConfig, devices } from '@playwright/test'

/**
 * E2E contra la aplicación real: el control plane (:8080) y un runner con fake-claude deben estar
 * arrancados (lo hace `scripts/e2e.sh`). La web se sirve con `vite preview`, que reenvía la API.
 */
export default defineConfig({
  testDir: 'e2e',
  testMatch: '**/*.e2e.ts',
  timeout: 120_000,
  expect: { timeout: 15_000 },
  retries: 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    // El navegador entra por el proxy que levanta la prueba (:4100 → :4173) para poder cortarle la
    // conexión.
    baseURL: 'http://localhost:4100',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npx vite preview --port 4173 --strictPort',
    url: 'http://localhost:4173',
    reuseExistingServer: false,
  },
})
