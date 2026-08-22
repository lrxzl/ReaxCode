import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './style.css'

// 拦截 target="_blank" 链接
// - MiniBrowser iframe 内：通知父窗口打开新 MiniBrowser 实例
// - WebView 内（Android）：通过 JavaBridge.openWebView 启动 FloatingWebView
document.addEventListener('click', (e) => {
  const link = e.target.closest('a[target="_blank"]')
  if (link && link.href) {
    e.preventDefault()
    if (typeof SubWebViewBridge !== 'undefined') {
      SubWebViewBridge.openWebView(link.href)
    } else if (window.parent !== window) {
      window.parent.postMessage({ type: 'MINI_BROWSER_OPEN_URL', url: link.href }, '*')
    } else {
      window.open(link.href, '_blank')
    }
  }
}, true)

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
