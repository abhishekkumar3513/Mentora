/// <reference types="vitest" />
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

const target = process.env.VITE_BACKEND || 'http://localhost:8090'
export default defineConfig({
  plugins: [react()],
  server: { port: 5173, proxy: { '/api': target, '/ws': { target, ws: true } } },
  test: { environment: 'jsdom', globals: true, setupFiles: './src/setupTests.ts' },
})
