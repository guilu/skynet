/// <reference types="vitest/config" />
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'
import { e2eApiUrl } from './e2e-config.js'

const E2E_API_URL = e2eApiUrl(process.env.E2E_API_PORT)
const API_PROXY = {
  '/api': E2E_API_URL,
  '/actuator': E2E_API_URL,
}

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: [
      // monaco-yaml (vía monaco-worker-manager) importa `monaco-editor/esm/vs/...`, la ruta de las
      // versiones anteriores a la 0.55; ahora el paquete exporta `monaco-editor/...` sin `esm/vs`.
      {
        find: /^monaco-editor\/esm\/vs\/(.*)$/,
        replacement: fileURLToPath(
          new URL('./node_modules/monaco-editor/esm/vs/$1', import.meta.url),
        ),
      },
    ],
  },
  // En desarrollo y E2E, el proxy apunta al puerto configurable del control plane.
  server: { proxy: API_PROXY },
  preview: { proxy: API_PROXY },
  // Monaco ocupa unos 2,7 MB, pero va en un trozo aparte que solo se descarga al abrir un diff o
  // un log (React.lazy); el aviso por defecto (500 kB) saltaría siempre por él.
  build: { chunkSizeWarningLimit: 3000 },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['./src/test/setup.ts'],
    // Por encima de los 5 s de espera de Testing Library (src/test/setup.ts), que un test lento
    // por carga no se corte antes de que su findBy termine.
    testTimeout: 20_000,
    // palette.test.ts lee los tokens de index.css (?raw) para compararlos con los suyos.
    css: { include: [/index\.css/] },
  },
})
