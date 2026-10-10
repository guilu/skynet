/// <reference types="vitest/config" />
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'

const API_PROXY = {
  '/api': 'http://localhost:8080',
  '/actuator': 'http://localhost:8080',
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
  // En desarrollo (y en la E2E, que usa `vite preview`), la API del control plane corre en :8080.
  server: { proxy: API_PROXY },
  preview: { proxy: API_PROXY },
  // Monaco ocupa unos 2,7 MB, pero va en un trozo aparte que solo se descarga al abrir un diff o
  // un log (React.lazy); el aviso por defecto (500 kB) saltaría siempre por él.
  build: { chunkSizeWarningLimit: 3000 },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['./src/test/setup.ts'],
    // palette.test.ts lee los tokens de index.css (?raw) para compararlos con los suyos.
    css: { include: [/index\.css/] },
  },
})
