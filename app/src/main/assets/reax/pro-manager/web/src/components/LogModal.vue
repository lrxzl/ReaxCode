<template>
  <div v-if="visible" class="modal-mask" @click.self="$emit('close')">
    <div class="modal-box">
      <div class="modal-head">
        {{ title }}
        <span v-if="streaming" class="stream-dot"></span>
      </div>
      <div class="modal-body logbox" ref="contentRef"><pre v-if="content">{{ content }}</pre><div v-else class="empty-mod">暂无日志</div></div>
      <div class="modal-foot">
        <button class="btn btn-outline btn-sm" @click="$emit('close')">关闭</button>
        <button class="btn btn-outline btn-sm" @click="handleCopy" :disabled="!content">复制末25行问AI</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, nextTick, onUnmounted } from 'vue'
import { useCopy } from '../composables/useCopy'

const props = defineProps({
  visible: Boolean,
  title: { type: String, default: '运行日志' },
  content: { type: String, default: '' },
  copyContext: { type: Object, default: null },
  logUrl: { type: String, default: '' },
  onUpdate: { type: Function, default: null }
})

defineEmits(['close'])

const { copy } = useCopy()
const contentRef = ref(null)
const streaming = ref(false)
let eventSource = null
let streamedContent = ''

const scrollToBottom = () => {
  if (contentRef.value) {
    contentRef.value.scrollTop = contentRef.value.scrollHeight
  }
}

const connectStream = () => {
  disconnectStream()
  if (!props.logUrl) return

  streamedContent = ''
  eventSource = new EventSource(props.logUrl)
  streaming.value = true

  eventSource.onmessage = (e) => {
    try {
      const data = JSON.parse(e.data)
      if (data.type === 'init') {
        streamedContent = data.content || ''
      } else if (data.type === 'chunk') {
        streamedContent += data.content
        if (streamedContent.length > 50000) {
          streamedContent = streamedContent.slice(-40000)
        }
      }
      props.onUpdate?.(streamedContent)
      nextTick(() => requestAnimationFrame(scrollToBottom))
    } catch {}
  }

  eventSource.onerror = () => {
    streaming.value = false
    eventSource?.close()
    eventSource = null
  }
}

const disconnectStream = () => {
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
  streaming.value = false
}

watch(() => props.visible, (v) => {
  if (v) {
    nextTick(() => {
      requestAnimationFrame(scrollToBottom)
      connectStream()
    })
  } else {
    disconnectStream()
  }
})

watch(() => props.content, () => {
  if (!eventSource) {
    nextTick(() => requestAnimationFrame(scrollToBottom))
  }
})

onUnmounted(() => disconnectStream())

const handleCopy = () => {
  const text = eventSource ? streamedContent : props.content
  if (!text) return
  const lines = text.split('\n').slice(-25).join('\n')
  const ctx = props.copyContext
  if (!ctx) { copy(lines, '已复制'); return }
  const isWin = window.navigator.platform.includes('Win')
  const sep = isWin ? '\\' : '/'
  copy([
    lines, '', '--- 调试信息 ---',
    `项目: ${ctx.projectName || ''}`, `模块: ${ctx.moduleName || '未命名'}`,
    `类型: ${ctx.moduleType === 'frontend' ? '前端' : '后端'}`, `路径: ${ctx.modulePath || ''}`,
    `脚本: ${ctx.modulePath || ''}${sep}startup${isWin ? '.bat' : '.sh'}`,
    `日志: ${ctx.modulePath || ''}${sep}startup${isWin ? '.bat.log' : '.sh.log'}`,
    `信息: ${ctx.modulePath || ''}${sep}pro-info.json`,
    `以上部分日志和调试信息，请你帮我解决日志中报错的问题。`,
  ].join('\n'), '已复制信息，可以去发给AI帮你处理了')
}
</script>

<style src="./LogModal.css" scoped></style>
