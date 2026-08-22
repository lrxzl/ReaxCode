
<template>
  <div class="page" :class="{ 'drop-active-page': dragging }" @dragover.prevent @drop.prevent="handleDrop">
    <header class="hd">
      <button class="btn-back" @click="goBack">←</button>
      <h1 class="hd-title">文件管理</h1>
      <button class="btn btn-outline btn-sm" @click="refresh">↻</button>
    </header>

    <!-- 面包屑 -->
    <div class="bc-bar">
      <template v-for="(crumb, i) in breadcrumbs" :key="i">
        <span v-if="i > 0" class="bc-sep">/</span>
        <span class="bc-item" :class="{ 'bc-el': crumb.name === '...' }" @click="navigateTo(crumb.path)">{{ crumb.name }}</span>
      </template>
      <button class="btn-copy" @click.stop="copy(currentPath, '路径')">📋</button>
    </div>

    <!-- 局域网提示 -->
    <div class="lan-hint" v-if="lanIps.length">
      局域网访问:
      <template v-for="(ip, i) in lanIps" :key="ip">
        <a :href="'http://' + ip + ':3456/files'" target="_blank">{{ ip }}:3456/files</a>
        <span v-if="i < lanIps.length - 1" class="lan-sep"> 或 </span>
      </template>
      <div class="lan-tip"> — 可从其他设备浏览、下载、拖拽上传文件</div>
    </div>

    <!-- 工具栏 -->
    <div class="toolbar">
      <button class="btn btn-outline btn-sm tbtn" @click="showNewFileModal = true">📄 新建文件</button>
      <button class="btn btn-outline btn-sm tbtn" @click="showNewDirModal = true">📁 新建目录</button>
      <button class="btn btn-outline btn-sm tbtn" @click="showSearchModal = true">🔍 搜索</button>
    </div>

    <!-- 拖拽上传区域 -->
    <div class="drop-zone" :class="{ 'drop-active': dragging }" @click="$refs.fileInput.click()">
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
      <span>点击或拖拽文件到此处传入文件</span>
      <input ref="fileInput" type="file" multiple style="display:none" @change="handleFileSelect" />
    </div>

    <div v-if="uploading" class="upload-progress">
      <div class="spinner"></div>上传中...
    </div>

    <div v-if="loading" class="loading"><div class="spinner"></div>加载中...</div>

    <div v-else class="flist">
      <div v-for="file in files" :key="file.path" class="fitem" @click="handleClick(file)">
        <span class="ficon">{{ file.isDirectory ? '📁' : '📄' }}</span>
        <div class="finfo">
          <span class="fname">{{ file.name }}</span>
          <span class="fmeta">
            <span v-if="!file.isDirectory">{{ formatSize(file.size) }}</span>
            <span v-if="file.modified">{{ formatDate(file.modified) }}</span>
          </span>
        </div>
        <div class="factions">
          <button class="btn-icon" @click.stop="copy(file.path, '路径')">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1"/></svg>
          </button>
          <button class="btn-icon" @click.stop="openMenu(file)">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><circle cx="12" cy="5" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="12" cy="19" r="2"/></svg>
          </button>
        </div>
      </div>
      <div v-if="!files.length" class="empty"><div class="empty-icon">📂</div><div style="font-size:13px;color:var(--text-muted)">空目录</div></div>
    </div>

    <!-- 编辑器 -->
    <div v-if="editingFile" class="editor-mask" @click.self="editingFile = null">
      <div class="editor-box">
        <div class="editor-hd">
          <span class="editor-path" :title="editingFile">{{ editingFile }}</span>
          <div style="display:flex;gap:5px;flex-shrink:0">
            <button class="btn btn-primary btn-sm" @click="saveFile" :disabled="saving">{{ saving ? '...' : '保存' }}</button>
            <button class="btn btn-outline btn-sm" @click="editingFile = null">关闭</button>
          </div>
        </div>
        <textarea v-model="editingContent" class="editor-body"></textarea>
      </div>
    </div>

    <!-- 新建文件 -->
    <div v-if="showNewFileModal" class="modal-mask" @click.self="showNewFileModal = false">
      <div class="modal-box">
        <div class="modal-head">新建文件</div>
        <div class="modal-body"><div class="field"><label>文件名</label><input v-model="newFileName" placeholder="example.txt" @keyup.enter="createFile" /></div></div>
        <div class="modal-foot">
          <button class="btn btn-outline btn-sm" @click="showNewFileModal = false">取消</button>
          <button class="btn btn-primary btn-sm" @click="createFile" :disabled="!newFileName">创建</button>
        </div>
      </div>
    </div>

    <!-- 新建目录 -->
    <div v-if="showNewDirModal" class="modal-mask" @click.self="showNewDirModal = false">
      <div class="modal-box">
        <div class="modal-head">新建目录</div>
        <div class="modal-body"><div class="field"><label>目录名</label><input v-model="newDirName" placeholder="new-folder" @keyup.enter="createDir" /></div></div>
        <div class="modal-foot">
          <button class="btn btn-outline btn-sm" @click="showNewDirModal = false">取消</button>
          <button class="btn btn-primary btn-sm" @click="createDir" :disabled="!newDirName">创建</button>
        </div>
      </div>
    </div>

    <!-- 搜索 -->
    <div v-if="showSearchModal" class="modal-mask" @click.self="showSearchModal = false">
      <div class="modal-box">
        <div class="modal-head">搜索文件</div>
        <div class="modal-body">
          <div class="field"><label>关键词</label><input v-model="searchKeyword" placeholder="文件名" @keyup.enter="doSearch" /></div>
          <div v-if="searchResults.length" class="sresults">
            <div v-for="r in searchResults" :key="r.path" class="sitem" @click="navigateToFile(r)">
              <span>{{ r.isDirectory ? '📁' : '📄' }}</span>
              <span style="flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ r.name }}</span>
            </div>
          </div>
        </div>
        <div class="modal-foot">
          <button class="btn btn-outline btn-sm" @click="showSearchModal = false">关闭</button>
          <button class="btn btn-primary btn-sm" @click="doSearch" :disabled="!searchKeyword">搜索</button>
        </div>
      </div>
    </div>

    <!-- 更多操作菜单 -->
    <div v-if="showMenuModal" class="modal-mask" @click.self="showMenuModal = false">
      <div class="modal-box menu-modal">
        <div class="modal-head">{{ menuFile?.name }}</div>
        <div class="menu-list">
          <a class="menu-item" :href="menuFileUrl" target="_blank" @click="showMenuModal = false">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 13v6a2 2 0 01-2 2H5a2 2 0 01-2-2V8a2 2 0 012-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/></svg>
            <span>新页中打开</span>
          </a>
          <div class="menu-item" @click="handleMenuRename">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 3a2.83 2.83 0 114 4L7.5 20.5 2 22l1.5-5.5L17 3z"/></svg>
            <span>修改文件名</span>
          </div>
          <a class="menu-item" :href="menuFileDownloadUrl" @click="showMenuModal = false">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
            <span>下载</span>
          </a>
          <div class="menu-item menu-danger" @click="handleMenuDelete">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 01-2 2H8a2 2 0 01-2-2L5 6"/><path d="M10 11v6"/><path d="M14 11v6"/><path d="M9 6V4a1 1 0 011-1h4a1 1 0 011 1v2"/></svg>
            <span>删除</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 确认删除 -->
    <div v-if="showDeleteConfirm" class="modal-mask" @click.self="showDeleteConfirm = false">
      <div class="modal-box">
        <div class="modal-head">确认删除</div>
        <div class="modal-body"><p style="font-size:13px;color:var(--text-secondary)">确定要删除 "{{ deleteTarget?.name }}" 吗？此操作不可恢复。</p></div>
        <div class="modal-foot">
          <button class="btn btn-outline btn-sm" @click="showDeleteConfirm = false">取消</button>
          <button class="btn btn-danger btn-sm" @click="confirmDelete">删除</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import * as filesApi from '../api/files'
import * as projectsApi from '../api/projects'
import { useToast } from '../composables/useToast'
import { useCopy } from '../composables/useCopy'

const route = useRoute()
const router = useRouter()
const toast = useToast()
const { copy } = useCopy()

const projectId = route.params.id
const project = ref(null)
const currentPath = ref('')
const files = ref([])
const loading = ref(false)
const editingFile = ref(null)
const editingContent = ref('')
const saving = ref(false)
const showNewFileModal = ref(false)
const newFileName = ref('')
const showNewDirModal = ref(false)
const newDirName = ref('')
const showSearchModal = ref(false)
const searchKeyword = ref('')
const searchResults = ref([])
const showMenuModal = ref(false)
const menuFile = ref(null)
const showDeleteConfirm = ref(false)
const deleteTarget = ref(null)
const lanIps = ref([])
const dragging = ref(false)
const uploading = ref(false)
const isDesktop = !('ontouchstart' in window) && !navigator.maxTouchPoints
let dragCounter = 0

const menuFileUrl = computed(() => {
  if (!menuFile.value) return '#'
  return `file:///${menuFile.value.path.replace(/\\/g, '/')}`
})
const menuFileDownloadUrl = computed(() => {
  if (!menuFile.value) return '#'
  return filesApi.getDownloadUrl(menuFile.value.path)
})

const breadcrumbs = computed(() => {
  if (!currentPath.value) return []
  const items = []; let r = currentPath.value
  while (r) {
    const i = Math.max(r.lastIndexOf('/'), r.lastIndexOf('\\'))
    if (i < 0) { items.unshift({ name: r, path: r }); break }
    items.unshift({ name: r.substring(i + 1), path: r }); r = r.substring(0, i)
  }
  const ri = items.findIndex(x => x.name === 'pro-manager')
  const all = ri >= 0 ? items.slice(ri) : items
  return all.length > 3 ? [{ name: '...', path: all[0].path }, ...all.slice(-2)] : all
})

const loadProject = async () => {
  try {
    if (projectId) { const r = await projectsApi.getProject(projectId); project.value = r.data }
    if (route.query.path) currentPath.value = route.query.path
    else if (project.value?.rootDir) currentPath.value = project.value.rootDir
    else { const r = await filesApi.getHomeDir(); currentPath.value = r.data }
    await loadFiles()
  } catch (e) { toast.error(e.message) }
  try { const r = await filesApi.getLanIp(); lanIps.value = Array.isArray(r.data) ? r.data : r.data ? [r.data] : [] } catch {}
}

const loadFiles = async () => {
  loading.value = true
  try { const r = await filesApi.listDir(currentPath.value); files.value = r.data || [] }
  catch (e) { toast.error(e.message); files.value = [] }
  finally { loading.value = false }
}

const refresh = () => loadFiles()
const navigateTo = (p) => { currentPath.value = p; loadFiles() }
const handleClick = (f) => { f.isDirectory ? navigateTo(f.path) : openFile(f.path) }

const openFile = async (fp) => { try { const r = await filesApi.readFile(fp); editingFile.value = fp; editingContent.value = r.data.content } catch (e) { toast.error(e.message) } }
const saveFile = async () => { saving.value = true; try { await filesApi.writeFile(editingFile.value, editingContent.value); toast.success('已保存') } catch (e) { toast.error(e.message) } finally { saving.value = false } }

const openMenu = (f) => { menuFile.value = f; showMenuModal.value = true }
const handleMenuRename = async () => {
  const f = menuFile.value; showMenuModal.value = false
  const n = prompt('新名称:', f.name); if (!n || n === f.name) return
  try { await filesApi.renameFile(f.path, n); toast.success('重命名成功'); loadFiles() } catch (e) { toast.error(e.message) }
}
const handleMenuDelete = () => { showMenuModal.value = false; deleteTarget.value = menuFile.value; showDeleteConfirm.value = true }
const confirmDelete = async () => {
  try { await filesApi.deleteFile(deleteTarget.value.path); toast.success('已删除'); showDeleteConfirm.value = false; deleteTarget.value = null; loadFiles() } catch (e) { toast.error(e.message) }
}
const createFile = async () => {
  if (!newFileName.value) return
  try { await filesApi.writeFile(currentPath.value + '/' + newFileName.value, ''); toast.success('已创建'); showNewFileModal.value = false; newFileName.value = ''; loadFiles() } catch (e) { toast.error(e.message) }
}
const createDir = async () => {
  if (!newDirName.value) return
  try { await filesApi.createDir(currentPath.value + '/' + newDirName.value); toast.success('已创建'); showNewDirModal.value = false; newDirName.value = ''; loadFiles() } catch (e) { toast.error(e.message) }
}
const doSearch = async () => {
  if (!searchKeyword.value) return
  try { const r = await filesApi.searchFiles(currentPath.value, searchKeyword.value); searchResults.value = r.data || [] } catch (e) { toast.error(e.message) }
}
const navigateToFile = (f) => {
  showSearchModal.value = false
  if (f.isDirectory) navigateTo(f.path)
  else { currentPath.value = f.path.substring(0, f.path.lastIndexOf('/')); loadFiles(); openFile(f.path) }
}
const goBack = () => {
  if (project.value && currentPath.value === project.value.rootDir) router.push(`/project/${projectId}`)
  else { const p = currentPath.value.substring(0, currentPath.value.lastIndexOf('/')); if (p) { currentPath.value = p; loadFiles() } else if (!projectId) router.push('/') }
}

const handleDragEnter = (e) => { e.preventDefault(); dragCounter++; dragging.value = true }
const handleDragLeave = (e) => { e.preventDefault(); dragCounter--; if (dragCounter <= 0) { dragging.value = false; dragCounter = 0 } }
const handleDrop = async (e) => {
  dragCounter = 0; dragging.value = false
  const droppedFiles = e.dataTransfer?.files
  if (!droppedFiles || !droppedFiles.length) return
  uploading.value = true
  try { await filesApi.uploadFiles(currentPath.value, droppedFiles); toast.success(`已上传 ${droppedFiles.length} 个文件`); loadFiles() }
  catch (err) { toast.error(err.message) }
  finally { uploading.value = false }
}
const handleFileSelect = async (e) => {
  const selectedFiles = e.target.files
  if (!selectedFiles || !selectedFiles.length) return
  uploading.value = true
  try { await filesApi.uploadFiles(currentPath.value, selectedFiles); toast.success(`已上传 ${selectedFiles.length} 个文件`); loadFiles() }
  catch (err) { toast.error(err.message) }
  finally { uploading.value = false; e.target.value = '' }
}

const formatSize = (b) => { if (b < 1024) return b + ' B'; if (b < 1048576) return (b / 1024).toFixed(1) + ' KB'; return (b / 1048576).toFixed(1) + ' MB' }
const formatDate = (d) => { if (!d) return ''; const dt = new Date(d); return dt.toLocaleDateString() + ' ' + dt.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) }

onMounted(() => {
  if (route.query.path) currentPath.value = route.query.path
  loadProject()
  if (isDesktop) {
    document.addEventListener('dragenter', handleDragEnter)
    document.addEventListener('dragleave', handleDragLeave)
  }
})
onUnmounted(() => {
  if (isDesktop) {
    document.removeEventListener('dragenter', handleDragEnter)
    document.removeEventListener('dragleave', handleDragLeave)
  }
})
</script>

<style src="./FileManager.css" scoped></style>
