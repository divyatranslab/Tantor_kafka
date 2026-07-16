import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const apiTarget = env.VITE_API_PROXY_TARGET || 'http://localhost:8443'
  const artifactTarget = env.VITE_ARTIFACT_PROXY_TARGET || 'http://localhost:8081'

  return {
    plugins: [react()],
    server: {
      proxy: {
        '/api/v1/artifacts': {
          target: artifactTarget,
          secure: false,
        },
        '/api': {
          target: apiTarget,
          secure: false,
        },
      },
    },
  }
})
