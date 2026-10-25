import { join, resolve } from 'node:path'

/** Rutas de la galería visual. Lo temporal (sesión, ids, texto capturado) vive fuera del repositorio. */
export const tmpDir = process.env.E2E_OUT ?? '/tmp/skynet-visual'
export const authFile = join(tmpDir, 'auth.json')
export const fixtureFile = join(tmpDir, 'fixture.json')
/** Texto visible y respuestas de la API de cada captura, para el escaneo de privacidad. */
export const renderedDir = join(tmpDir, 'rendered')
/**
 * Dónde se escribe la galería: docs/screenshots, o SCREENSHOTS_OUT para generarla fuera del
 * repositorio (una aceptación no debe tocar las capturas versionadas).
 */
export const galleryDir = process.env.SCREENSHOTS_OUT
  ? resolve(process.env.SCREENSHOTS_OUT)
  : resolve(import.meta.dirname, '../../docs/screenshots')

/** Ids que deja fixture.setup.ts para los preparadores. */
export interface Fixture {
  projectId: string
  emptyProjectId: string
  workItemId: string
  workflowKey: string
  publishedVersionId: string
  draftVersionId: string
  approvalId: string
  runs: { completed: string; documented: string; failed: string; active: string }
  agents: { completed: string; report: string; memory: string }
  /** Hora (ms) a la que se para el reloj del navegador. */
  frozenAt: number
}
