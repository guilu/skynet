/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

const API_PROXY = {
  '/api': 'http://localhost:8080',
  '/actuator': 'http://localhost:8080',
}

export default defineConfig({
  plugins: [react()],
  // En desarrollo (y en la E2E, que usa `vite preview`), la API del control plane corre en :8080.
  server: { proxy: API_PROXY },
  preview: { proxy: API_PROXY },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['./src/test/setup.ts'],
  },
})
