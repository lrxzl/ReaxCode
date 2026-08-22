
<template>
  <div v-if="visible" class="fp-mask" @click.self="handleCancel">
    <div class="fp-box">
      <div class="fp-hd">
        <span class="fp-title">{{ title }}</span>
        <button class="fp-close" @click="handleCancel">✕</button>
      </div>

      <div class="fp-input-row">
        <input v-model="inputPath" placeholder="输入路径" @keyup.enter="navigateTo(inputPath)" class="fp-input" />
        <button class="btn btn-primary btn-sm" @click="navigateTo(inputPath)">前往</button>
      </div>

      <div class="fp-bc">
        <span v-for="(crumb, idx) in breadcrumbs" :key="idx">
          <a v-if="idx < breadcrumbs.length - 1" @click="navigateTo(crumb.path)" class="fp-bc-link">{{ crumb.name }}</a>
          <span v-else class="fp-bc-cur">{{ crumb.name }}</span>
          <span v-if="idx < breadcrumbs.length - 1" class="fp-bc-sep">\</span>
        </span>
      </div>

      <div class="fp-list" ref="listRef">
        <div v-if="loading" class="fp-loading"><div class="spinner"></div></div>
        <template v-else>
          <div v-if="parentPath" class="fp-item fp-parent" @click="navigateTo(parentPath)">
            <span class="fp-icon">📁</span><span class="fp-name">..</span>
          </div>
          <div v-if="showRoots && roots.length" class="fp-group">
            <div class="fp-group-label">驱动器</div>
            <div v-for="root in roots" :key="root.path" class="fp-item" @click="navigateTo(root.path)">
              <span class="fp-icon">💿</span><span class="fp-name">{{ root.name }}</span>
            </div>
          </div>
          <div v-for="item in folders" :key="item.path" class="fp-item" @click="navigateTo(item.path)">
            <span class="fp-icon">📁</span><span class="fp-name">{{ item.name }}</span>
          </div>
          <div v-if="!loading && !folders.length && !parentPath && !showRoots" class="fp-empty">空目录</div>
        </template>
      </div>

      <div class="fp-ft">
        <span class="fp-sel" v-if="currentPath">{{ currentPath }}</span>
        <div class="fp-acts">
          <button class="btn btn-outline btn-sm" @click="handleCancel">取消</button>
          <button class="btn btn-primary btn-sm" @click="handleConfirm" :disabled="!currentPath">选择</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, nextTick, computed } from 'vue'
import { listDir, getRoots, getHomeDir } from '../api/files'

const props = defineProps({
  visible: { type: Boolean, default: false },
  title: { type: String, default: '选择文件夹' },
  initialPath: { type: String, default: '' }
})

const emit = defineEmits(['confirm', 'cancel'])

const loading = ref(false)
const currentPath = ref('')
const inputPath = ref('')
const folders = ref([])
const roots = ref([])
const parentPath = ref('')
const showRoots = ref(false)
const listRef = ref(null)

const isWindows = navigator.platform.toUpperCase().indexOf('WIN') >= 0

const breadcrumbs = computed(() => {
  if (!currentPath.value) return []
  const raw = currentPath.value.replace(/\\/g, '/')
  const parts = raw.split('/').filter(Boolean)
  const result = []
  if (isWindows && parts.length > 0 && parts[0].endsWith(':')) {
    let acc = parts[0] + '/'
    result.push({ name: acc, path: acc })
    for (let i = 1; i < parts.length; i++) { acc += parts[i] + '/'; result.push({ name: parts[i], path: acc }) }
  } else {
    let acc = '/'
    result.push({ name: '/', path: '/' })
    for (let i = 0; i < parts.length; i++) { acc += parts[i] + '/'; result.push({ name: parts[i], path: acc }) }
  }
  return result
})

watch(() => props.visible, async (val) => {
  if (val) {
    loading.value = true; folders.value = []; currentPath.value = ''; inputPath.value = ''; parentPath.value = ''; showRoots.value = false
    try { const rr = await getRoots(); roots.value = (rr && rr.data) ? rr.data : [] } catch { roots.value = [] }
    let tp = ''
    if (props.initialPath) tp = getParentPath(props.initialPath)
    if (tp) await doNavigate(tp)
    else {
      try { const hr = await getHomeDir(); const hp = (hr && hr.data) ? hr.data : null; if (hp) await doNavigate(hp); else if (roots.value.length) await doNavigate(roots.value[0].path) }
      catch { if (roots.value.length) await doNavigate(roots.value[0].path) }
    }
    loading.value = false
  }
})

const getParentPath = (p) => {
  if (!p) return ''
  const sep = p.includes('/') ? '/' : '\\'
  const raw = sep === '/' ? p : p.replace(/\\/g, '/')
  const parts = raw.split('/').filter(Boolean)
  if (parts.length <= 1) return ''
  parts.pop()
  return sep === '\\' ? parts.join('\\') : '/' + parts.join('/')
}

const doNavigate = async (dirPath) => {
  if (!dirPath) return
  loading.value = true; folders.value = []; parentPath.value = ''; showRoots.value = false
  try {
    let items = []
    const r = await Promise.race([listDir(dirPath), new Promise((_, rej) => setTimeout(() => rej(new Error('超时')), 5000))])
    if (r && r.data) items = r.data; else if (Array.isArray(r)) items = r
    folders.value = items.filter(i => i.isDirectory)
    const n = dirPath.replace(/[/\\]$/, '')
    currentPath.value = n; inputPath.value = n
    computeParent(n)
    await nextTick()
    if (listRef.value) listRef.value.scrollTop = 0
  } catch { currentPath.value = dirPath; inputPath.value = dirPath; folders.value = []; parentPath.value = ''; showRoots.value = isWindows }
  finally { loading.value = false }
}

const computeParent = (n) => {
  if (isWindows) {
    const raw = n.replace(/\\/g, '/'); const parts = raw.split('/').filter(Boolean)
    if (parts.length <= 1 || (parts.length === 2 && parts[1] === '')) { parentPath.value = ''; showRoots.value = true }
    else parentPath.value = parts.slice(0, -1).join('\\')
  } else {
    const parts = n.split('/').filter(Boolean)
    parentPath.value = parts.length <= 1 ? '' : '/' + parts.slice(0, -1).join('/')
  }
}

const navigateTo = async (dp) => { await doNavigate(dp) }
const handleConfirm = () => { if (currentPath.value) emit('confirm', currentPath.value) }
const handleCancel = () => { emit('cancel') }
</script>

<style src="./FolderPicker.css" scoped></style>
