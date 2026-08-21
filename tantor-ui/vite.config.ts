import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const envDir = '..'
  const env = loadEnv(mode, envDir, '')
  const apiTarget = env.VITE_API_PROXY_TARGET || 'http://localhost:8443'
  const artifactTarget = env.VITE_ARTIFACT_PROXY_TARGET || 'http://localhost:8081'

  return {
    envDir,
    plugins: [react()],
    build: {
      modulePreload: false,
      chunkSizeWarningLimit: 800,
      rollupOptions: {
        output: {
          manualChunks(id) {
            if (id.includes('node_modules')) {
              if (id.includes('react') || id.includes('react-dom') || id.includes('react-router')) {
                return 'react-vendor';
              }
              if (id.includes('recharts')) {
                return 'chart-vendor';
              }
              if (id.includes('keycloak-js')) {
                return 'auth-vendor';
              }
              if (id.includes('lucide-react')) {
                return 'ui-icons';
              }
              return 'vendor'; // all other dependencies
            }
          }
        }
      }
    },
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
    preview: {
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
