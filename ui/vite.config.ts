import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import { loadEnv } from 'vite'

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const backendOrigin =
    env.VITE_BACKEND_ORIGIN ||
    env.BACKEND_ORIGIN ||
    `${env.VITE_BACKEND_PROTOCOL || env.BACKEND_PROTOCOL || 'http'}://${env.VITE_BACKEND_HOST || env.BACKEND_HOST || 'localhost'}:${env.VITE_BACKEND_PORT || env.BACKEND_PORT || '8080'}`

  return {
    plugins: [react()],
    server: {
      proxy: {
        '/api': {
          target: backendOrigin,
          changeOrigin: true,
        }
      }
    },
    test: {
      environment: 'jsdom',
      globals: false,
      setupFiles: './src/test/setup.ts',
      esbuild: {
        jsx: 'automatic',
        jsxImportSource: 'react',
      },
    },
  }
})
