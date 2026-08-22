import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    open: false, // 自动打开浏览器
    proxy: {
'/api': { target: 'http://localhost:3456', changeOrigin: true },
      '/ws': { target: 'ws://localhost:3456', ws: true }
    }
  },
  build: { outDir: 'dist' }
})
