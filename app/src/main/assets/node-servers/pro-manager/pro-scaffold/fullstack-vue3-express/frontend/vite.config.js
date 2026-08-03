import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'

// 编译错误监控插件（监听 Vite HMR 推送的错误）
function viteErrorMonitor() {
  let latestErrors = []
  let compileTimer = null

  return {
    name: 'vite-error-monitor',
    configureServer(server) {
      // 1. 劫持 ws.send，捕获编译错误
      const originalSend = server.ws.send.bind(server.ws)
      server.ws.send = (payload) => {
        if (payload?.type === 'error') {
          latestErrors = [payload.err]
          clearTimeout(compileTimer)   // 有错误，取消成功判定
          server.config.logger.info('[vite-error-monitor] 捕获编译错误: ' + payload.err.message)
        } else if (payload && (payload.type === 'update' || payload.type === 'full-reload')) {
          latestErrors = []
          clearTimeout(compileTimer)
        }
        originalSend(payload)
      }

      // 2. 监听文件变化，通过防抖自动判定编译成功
      if (server.watcher) {
        server.watcher.on('change', onFileChange)
        server.watcher.on('add', onFileChange)
      }

      function onFileChange(path) {
        clearTimeout(compileTimer)
        // 延迟 500ms，如果期间没有 error 消息，视为编译成功
        compileTimer = setTimeout(() => {
          latestErrors = []
        }, 500)
      }

      // 3. 提供查询接口
      server.middlewares.use('/api/compile-info', (req, res, next) => {
        res.setHeader('Content-Type', 'application/json')
        res.setHeader('Access-Control-Allow-Origin', '*')
        res.end(JSON.stringify({
          hasError: latestErrors.length > 0,
          errors: latestErrors
        }))
      })

      // 清理
      server.httpServer?.on('close', () => {
        latestErrors = []
        clearTimeout(compileTimer)
      })
    }
  }
}
export default defineConfig({
  plugins: [
    vue(),
    viteErrorMonitor(),  // 添加错误监控插件
  ],
  server: {
    host: '0.0.0.0',
    port: parseInt(process.env.PORT) || 5173,
    proxy: {
      '/api': { target: 'http://localhost:3000', changeOrigin: true }
    }
  },
  build: { outDir: 'dist' },
})

