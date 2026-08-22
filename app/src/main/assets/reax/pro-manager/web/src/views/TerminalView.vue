
<template>
  <div class="page">
    <header class="hd">
      <button class="btn-back" @click="$router.push(projectId ? `/project/${projectId}` : '/')">←</button>
      <h1 class="hd-title">终端</h1>
      <button v-if="!connected" class="btn btn-primary btn-sm" @click="connectTerminal">连接</button>
      <button v-else class="btn btn-outline btn-sm" style="color:var(--danger);border-color:rgba(248,113,113,0.3)" @click="disconnectTerminal">断开</button>
    </header>

    <div class="term-wrap">
      <div class="term-output" ref="outputRef">
        <div v-for="(line, i) in outputLines" :key="i" :class="['oline', line.type]">{{ line.text }}</div>
      </div>
      <div class="term-input">
        <span class="prompt">$</span>
        <input v-model="commandInput" placeholder="输入命令..." :disabled="!connected" @keyup.enter="sendCommand" ref="inputRef" />
        <button class="btn btn-primary btn-sm" :disabled="!connected || !commandInput" @click="sendCommand">执行</button>
      </div>
    </div>

    <div class="sec">
      <div class="sec-title" style="margin-bottom:6px">快捷命令</div>
      <div class="qlist">
        <button v-for="cmd in quickCommands" :key="cmd.command" class="btn btn-outline btn-sm qbtn" :disabled="!connected" @click="runQuickCommand(cmd.command)">
          {{ cmd.label }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import * as projectsApi from '../api/projects'
import { useToast } from '../composables/useToast'

const route = useRoute()
const toast = useToast()
const projectId = route.params.id
const project = ref(null)
const connected = ref(false)
const commandInput = ref('')
const outputLines = ref([])
const outputRef = ref(null)
const inputRef = ref(null)
let ws = null

const quickCommands = [
  { label: 'ls', command: 'ls -la' },
  { label: 'npm -v', command: 'npm -v' },
  { label: 'node -v', command: 'node -v' },
  { label: 'df -h', command: 'df -h' },
  { label: 'clear', command: 'clear' },
  { label: 'pwd', command: 'pwd' }
]

const loadProject = async () => {
  if (!projectId) return
  try { const r = await projectsApi.getProject(projectId); project.value = r.data } catch (e) { toast.error(e.message) }
}

const addOutput = (text, type = 'stdout') => {
  outputLines.value.push({ text, type })
  if (outputLines.value.length > 1000) outputLines.value = outputLines.value.slice(-500)
  nextTick(() => { if (outputRef.value) outputRef.value.scrollTop = outputRef.value.scrollHeight })
}

const connectTerminal = () => {
  if (ws) return
  const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  ws = new WebSocket(`${proto}//${window.location.host}/ws/terminal`)
  ws.onopen = () => { connected.value = true; addOutput('已连接', 'system'); ws.send(JSON.stringify({ type: 'start', cwd: project.value?.rootDir || '.' })) }
  ws.onmessage = (e) => {
    try {
      const m = JSON.parse(e.data)
      if (m.type === 'stdout') addOutput(m.data, 'stdout')
      else if (m.type === 'stderr') addOutput(m.data, 'stderr')
      else if (m.type === 'exit') addOutput(`退出: ${m.code}`, 'system')
      else if (m.type === 'started') addOutput(`Shell PID: ${m.pid}`, 'system')
      else if (m.type === 'error') addOutput(`错误: ${m.message}`, 'error')
    } catch { addOutput(e.data, 'stdout') }
  }
  ws.onclose = () => { connected.value = false; addOutput('已断开', 'system'); ws = null }
  ws.onerror = () => addOutput('连接错误', 'error')
}

const disconnectTerminal = () => { if (ws) { ws.close(); ws = null } }
const sendCommand = () => {
  if (!ws || !commandInput.value) return
  addOutput(`$ ${commandInput.value}`, 'command')
  ws.send(JSON.stringify({ type: 'input', data: commandInput.value + '\n' }))
  commandInput.value = ''
}
const runQuickCommand = (c) => { commandInput.value = c; sendCommand() }

onMounted(() => { if (projectId) loadProject() })
onUnmounted(() => { disconnectTerminal() })
</script>

<style src="./TerminalView.css" scoped></style>
