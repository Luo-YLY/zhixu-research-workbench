import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1', port: 5173, strictPort: true,
    proxy: {
      '/api': { target: 'http://127.0.0.1:18081', changeOrigin: false },
      '/actuator': { target: 'http://127.0.0.1:18081', changeOrigin: false },
    },
  },
  build: { sourcemap: false },
})
