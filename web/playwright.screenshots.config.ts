import { defineConfig } from '@playwright/test'
import { authFile } from './visual/paths.ts'
import { inventory, variants } from './visual/inventory.ts'

/**
 * Galería visual (VS-A), aparte de las E2E: `npm run screenshots` lo arranca todo
 * (scripts/screenshots.sh). Un proyecto siembra los datos de demostración y otros cuatro capturan
 * cada pantalla en escritorio y móvil, claro y oscuro. No compara píxeles: falla si una pantalla
 * no carga o falta, no si cambia su diseño.
 */
const port = Number(process.env.SCREENSHOTS_WEB_PORT ?? 4174)
const baseURL = `http://localhost:${port}`

export default defineConfig({
  testDir: 'visual',
  timeout: 120_000,
  expect: { timeout: 20_000 },
  retries: 0,
  workers: 2,
  reporter: 'list',
  // Nada de traces, vídeos ni capturas de fallo: no hacen falta y no deben acabar en la galería.
  outputDir: `${process.env.E2E_OUT ?? '/tmp/skynet-visual'}/test-results`,
  use: {
    baseURL,
    trace: 'off',
    video: 'off',
    screenshot: 'off',
    locale: 'es-ES',
    timezoneId: 'Europe/Madrid',
    contextOptions: { reducedMotion: 'reduce' },
    deviceScaleFactor: 1,
    // Solo para entornos con un Chromium distinto del de @playwright/test.
    launchOptions: process.env.SCREENSHOTS_CHROMIUM
      ? { executablePath: process.env.SCREENSHOTS_CHROMIUM }
      : {},
  },
  projects: [
    { name: 'fixture', testMatch: 'fixture.setup.ts' },
    ...variants().map(({ viewport, theme }) => ({
      name: `${viewport}-${theme}`,
      testMatch: 'screenshots.spec.ts',
      dependencies: ['fixture'],
      use: {
        viewport: inventory.viewports[viewport],
        isMobile: viewport === 'mobile',
        hasTouch: viewport === 'mobile',
        colorScheme: theme,
        storageState: authFile,
      },
    })),
  ],
  webServer: {
    command: `npx vite preview --port ${port} --strictPort`,
    url: baseURL,
    reuseExistingServer: false,
  },
})
