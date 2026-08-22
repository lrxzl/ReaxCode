import { ref, onMounted, onUnmounted } from 'vue'

let eventSource = null
const listeners = new Set()
const connected = ref(false)

function connect() {
  if (eventSource) return
  const base = window.location.origin
  eventSource = new EventSource(`${base}/api/events`)

  eventSource.onmessage = (e) => {
    try {
      const data = JSON.parse(e.data)
      for (const fn of listeners) fn(data)
    } catch {}
  }

  eventSource.onopen = () => { connected.value = true }
  eventSource.onerror = () => {
    connected.value = false
    eventSource.close()
    eventSource = null
    setTimeout(connect, 3000)
  }
}

function disconnect() {
  if (eventSource) {
    eventSource.close()
    eventSource = null
    connected.value = false
  }
}

export function useStatusStream(onChange) {
  onMounted(() => {
    listeners.add(onChange)
    connect()
  })

  onUnmounted(() => {
    listeners.delete(onChange)
    if (listeners.size === 0) disconnect()
  })

  return { connected }
}
