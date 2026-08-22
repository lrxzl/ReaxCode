
<template>
  <div class="page">
    <header class="hd">
      <button class="btn-back" @click="$router.push('/')">←</button>
      <h1 class="hd-title">{{ project?.projectName || '加载中...' }}</h1>
      <span v-if="project" :class="['st-dot', project.isRunning ? 'st-on' : 'st-off']"></span>
    </header>

    <div v-if="!project" class="loading"><div class="spinner"></div>加载中...</div>

    <template v-else>
      <!-- 信息栏 -->
<!--      <div class="info-bar">
        <span class="itag">{{ getCreationMethodLabel(project.creationMethod) }}</span>
        <span class="itag">{{ (project.modules || []).length }}模块</span>
        <span :class="['itag', project.isRunning ? 'itag-on' : 'itag-off']">
          {{ project.isRunning ? '运行中' : '已停止' }}
        </span>
      </div>-->
      <div class="path-bar">
        <span class="path-text" :title="project.rootDir">{{ project.rootDir }}</span>
        <button class="btn-copy" @click="copy(project.rootDir, '模块路径')">📋</button>
      </div>

      <!-- 操作栏 -->
      <div class="ops">
        <button class="opbtn" @click="handleSync" :disabled="actionLoading">↻ 刷新</button>
        <a class="opbtn" :href="`/project/${id}/files`" target="_blank" style="text-decoration:none">📁 文件</a>
        <button class="opbtn" @click="$router.push(`/project/${id}/git`)">⎇ Git</button>
      </div>

      <!-- 模块 -->
      <div class="sec">
        <div class="sec-hd">
          <span class="sec-title">模块</span>
          <button class="btn-add" @click="showAddModule = true">+ 添加</button>
        </div>

        <div v-if="showAddModule" class="add-box">
          <div class="add-top">
            <span>添加模块</span>
            <button class="btn-add" @click="showAddModule = false">取消</button>
          </div>
          <div class="field">
            <label>类型</label>
            <select v-model="addModuleForm.type">
              <option value="frontend">前端</option>
              <option value="server">后端</option>
            </select>
          </div>
          <div class="field">
            <label>目录</label>
            <div class="frow">
              <input v-model="addModuleForm.dir" placeholder="填 . 表示根目录" />
              <button class="btn btn-outline btn-sm" @click="openAddDirPicker">选择</button>
            </div>
          </div>
          <button class="btn btn-primary" style="width:100%" :disabled="!addModuleForm.dir || addingModule" @click="handleAddModule">
            {{ addingModule ? '添加中...' : '添加' }}
          </button>
        </div>

        <div v-for="(mod, idx) in (project.modules || [])" :key="idx" class="mod-item">
          <div class="mod-hd">
            <span class="mod-icon">{{ mod.type === 'frontend' ? '◉' : '◎' }}</span>
            <span class="mod-name">{{ mod.name || `模块${idx + 1}` }}</span>
            <span :class="['mod-st', mod.status === 'running' ? 'mod-on' : 'mod-off']">
              {{ mod.status === 'running' ? '运行中' : '已停止' }}
            </span>
          </div>

          <!-- 展示模式 -->
          <div v-if="editingModule !== idx" class="mod-body">
            <div class="mrow">
              <span class="mlbl">目录</span>
              <a class="mval mlink" :href="`/project/${id}/files?path=${encodeURIComponent(getModuleFullPath(mod))}`" target="_blank" :title="getModuleFullPath(mod)">{{ mod.dir }}</a>
              <button class="btn-copy" @click="copy(getModuleFullPath(mod), '路径')">📋</button>
            </div>
            <div class="mrow">
              <span class="mlbl">命令</span>
              <span class="mval mono">{{ mod.startupScript || '未配置' }}</span>
              <button class="btn-copy" @click="showLog(idx)">📄</button>
            </div>
            <div class="mrow" v-if="mod.port">
              <span class="mlbl">端口</span>
              <div class="mval" style="display:flex;gap:4px;flex-wrap:wrap">
                <a :href="`http://localhost:${mod.port}`" target="_blank" class="mlink">{{ mod.port }}</a>
                <template v-if="mod.ports?.length">
                  <template v-for="(p, pi) in mod.ports.filter(p => p !== mod.port)" :key="pi">
                    <span style="color:var(--text-muted)">→</span>
                    <a :href="`http://localhost:${p}`" target="_blank" class="mlink">{{ p }}</a>
                  </template>
                </template>
              </div>
            </div>
            <div v-if="mod.status === 'running' && getAccessLinks(mod).length > 0" class="mrow">
              <span class="mlbl">访问</span>
              <div class="mval" style="display:flex;gap:4px;flex-wrap:wrap">
                <template v-for="(link, li) in getAccessLinks(mod)" :key="li">
                  <a v-if="formatLink(link)" :href="link" target="_blank" class="mlink">{{ formatLink(link) }}</a>
                </template>
              </div>
            </div>
            <div class="mod-ops">
              <button v-if="!mod.pid || mod.pid <= 0" class="btn btn-outline btn-xs" style="color:var(--success);border-color:var(--success)" :disabled="moduleLoading === idx" @click="handleModuleStart(idx)">
                {{ moduleLoading === idx ? '...' : '启动' }}
              </button>
              <button v-else class="btn btn-outline btn-xs" style="color:var(--danger);border-color:var(--danger)" :disabled="moduleLoading === idx" @click="handleModuleStop(idx)">
                {{ moduleLoading === idx ? '...' : '停止' }}
              </button>
              <button class="btn btn-outline btn-xs" @click="startEditModule(idx)" :disabled="editingModule !== null">编辑</button>
              <button class="btn btn-outline btn-xs" style="color:var(--danger)" @click="handleRemoveModule(idx)">移除</button>
            </div>
          </div>

          <!-- 编辑模式 -->
          <div v-else class="mod-body">
            <div class="field"><label>名称</label><input v-model="editForm.name" /></div>
            <div class="field">
              <label>目录</label>
              <div class="frow">
                <input v-model="editForm.dir" placeholder="./frontend" />
                <button class="btn btn-outline btn-sm" @click="openEditDirPicker">选择</button>
              </div>
            </div>
            <div class="field"><label>启动脚本</label><textarea class="mono" v-model="editForm.startupScript" rows="6" placeholder="startup 脚本内容"></textarea></div>
            <div class="field"><label>端口</label><input v-model.number="editForm.port" type="number" placeholder="0" /></div>
            <div class="fops">
              <button class="btn btn-outline btn-sm" @click="editingModule = null">取消</button>
              <button class="btn btn-primary btn-sm" :disabled="savingModule" @click="handleSaveModule">{{ savingModule ? '...' : '保存' }}</button>
            </div>
          </div>
        </div>

        <div v-if="(project.modules || []).length === 0" class="empty-mod">
          还没有模块
          <button class="btn btn-primary btn-sm" style="margin-top:8px" @click="handleScanModules" :disabled="scanningModules">
            {{ scanningModules ? '扫描中...' : '扫描模块' }}
          </button>
        </div>
      </div>

      <!-- Git -->
      <div class="sec sec-git">
        <div class="sec-hd">
          <span class="sec-title">Git</span>
          <button class="btn-add" @click="$router.push(`/project/${id}/git`)">管理</button>
        </div>
        <div v-if="project.gitUrl">
          <div class="mrow">
            <span class="mlbl">地址</span>
            <span class="mval mono" style="word-break:break-all;flex:1;min-width:0">{{ project.gitUrl }}</span>
          </div>
          <button class="btn btn-primary btn-sm" style="margin-top:6px;width:100%" :disabled="pulling" @click="handlePull">
            {{ pulling ? '拉取中...' : '拉取最新代码' }}
          </button>
        </div>
        <div v-else style="text-align:center;padding:8px;font-size:12px;color:var(--text-muted)">未配置，点击管理进行配置</div>
      </div>
    </template>

    <FolderPicker :visible="showFolderPicker" :title="folderPickerTitle" :initialPath="folderPickerInitial" @confirm="handleFolderPick" @cancel="showFolderPicker = false" />

    <LogModal
      :visible="showLogModal"
      :title="logModalTitle"
      :content="logModalContent"
      :copy-context="logCopyContext"
      :log-url="logStreamUrl"
      :on-update="(v) => { logModalContent = v }"
      @close="showLogModal = false"
    />

    <!-- 扫描模块 -->
    <div v-if="showScanModal" class="modal-mask" @click.self="showScanModal = false">
      <div class="modal-box">
        <div class="modal-head">扫描到 {{ scannedModules.length }} 个模块</div>
        <div class="modal-body">
          <div v-if="scannedModules.length === 0" class="empty-mod">未检测到模块</div>
          <template v-else>
            <label class="check-all"><input type="checkbox" :checked="allScannedSelected" @change="toggleAllScanned" /><span>全选</span></label>
            <div class="scan-list">
              <div v-for="(mod, idx) in scannedModules" :key="idx" class="scan-item">
                <label class="scan-check"><input type="checkbox" v-model="mod.selected" /><span>📁 {{ mod.name }}</span></label>
                <select v-model="mod.type" style="width:auto;padding:3px 6px;font-size:11px"><option value="frontend">前端</option><option value="server">后端</option></select>
              </div>
            </div>
          </template>
        </div>
        <div class="modal-foot">
          <button class="btn btn-outline btn-sm" @click="showScanModal = false">取消</button>
          <button class="btn btn-primary btn-sm" :disabled="selectedCount === 0 || addingScannedModules" @click="handleAddScannedModules">
            {{ addingScannedModules ? '...' : `添加 (${selectedCount})` }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { useProjectStore } from '../stores/project'
import { useToast } from '../composables/useToast'
import { useCopy } from '../composables/useCopy'
import { useStatusStream } from '../composables/useStatusStream'
import FolderPicker from '../components/FolderPicker.vue'
import LogModal from '../components/LogModal.vue'

const route = useRoute()
const store = useProjectStore()
const toast = useToast()
const { copy } = useCopy()

const id = route.params.id
const project = ref(null)
const actionLoading = ref(false)
const moduleLoading = ref(null)

const pulling = ref(false)

const showAddModule = ref(false)
const addingModule = ref(false)
const addModuleForm = ref({ name: '前端', type: 'frontend', dir: '' })

watch(() => addModuleForm.value.type, (t) => { addModuleForm.value.name = t === 'frontend' ? '前端' : '后端' })

const editingModule = ref(null)
const savingModule = ref(false)
const editForm = ref({ name: '', dir: '', startupScript: '', port: 0 })

const showLogModal = ref(false)
const logModalIndex = ref(null)
const logModalContent = ref('')
const logModalTitle = computed(() => logModalIndex.value !== null ? '运行日志' : '启动失败')
const logStreamUrl = computed(() => {
  if (logModalIndex.value === null || !project.value) return ''
  return `/api/projects/${id}/log/${logModalIndex.value}/stream`
})
const logCopyContext = computed(() => {
  if (logModalIndex.value === null || !project.value) return null
  const mod = project.value.modules[logModalIndex.value]
  if (!mod) return null
  const root = (project.value.rootDir || '').replace(/\\/g, '/')
  const modDir = (mod.dir || '').replace(/^\.\//, '').replace(/\/+$/, '')
  return {
    projectName: project.value.projectName,
    moduleName: mod.name || '未命名',
    moduleType: mod.type,
    modulePath: modDir ? `${root}/${modDir}` : root
  }
})

const showFolderPicker = ref(false)
const folderPickerTitle = ref('选择文件夹')
const folderPickerInitial = ref('')
const folderPickerTarget = ref('')

const scanningModules = ref(false)
const showScanModal = ref(false)
const scannedModules = ref([])
const addingScannedModules = ref(false)

const selectedCount = computed(() => scannedModules.value.filter(m => m.selected).length)
const allScannedSelected = computed(() => scannedModules.value.length > 0 && scannedModules.value.every(m => m.selected))

const toggleAllScanned = () => { const v = !allScannedSelected.value; scannedModules.value.forEach(m => { m.selected = v }) }

const getModuleFullPath = (mod) => {
  const root = (project.value?.rootDir || '').replace(/\\/g, '/').replace(/\/+$/, '')
  const dir = (mod.dir || '').replace(/^\.\//, '')
  return dir ? `${root}/${dir}` : root
}

const loadProject = async () => {
  try {
    project.value = await store.fetchProject(id)
  } catch (err) { toast.error(err.message) }
}

onMounted(async () => {
  await loadProject()
  try { await store.syncStatus(id); await loadProject() } catch {}
})

useStatusStream((data) => {
  if (data.projectIndex === Number(id)) {
    loadProject()
  }
})

const handleModuleStart = async (idx) => {
  moduleLoading.value = idx
  try {
    const r = await store.startProject(id, String(idx))
    if (r?.error) {
      toast.error(`启动失败: ${r.error}`)
    } else {
      toast.success('模块已启动')
    }
    logModalIndex.value = idx
    showLogModal.value = true
    await refreshLog()
    await loadProject()
  } catch (err) {
    toast.error(err.message)
    logModalIndex.value = idx
    showLogModal.value = true
    await refreshLog()
  } finally {
    moduleLoading.value = null
  }
}

const handleModuleStop = async (idx) => {
  moduleLoading.value = idx
  try { await store.stopProject(id, String(idx)); toast.success('模块已停止'); await loadProject() }
  catch (err) { toast.error(err.message) }
  finally { moduleLoading.value = null }
}

const handleSync = async () => {
  actionLoading.value = true
  try { await store.syncStatus(id); await loadProject(); toast.success('已同步') }
  catch (err) { toast.error(err.message) }
  finally { actionLoading.value = false }
}

const handlePull = async () => {
  pulling.value = true
  try { await store.pullProject(id); await loadProject(); toast.success('代码已更新') }
  catch (err) { toast.error(err.message) }
  finally { pulling.value = false }
}

const openAddDirPicker = () => { folderPickerTitle.value = '选择模块目录'; folderPickerInitial.value = project.value?.rootDir || ''; folderPickerTarget.value = 'add'; showFolderPicker.value = true }
const openEditDirPicker = () => { folderPickerTitle.value = '选择模块目录'; folderPickerInitial.value = project.value?.rootDir || ''; folderPickerTarget.value = 'edit'; showFolderPicker.value = true }

const normalizeDir = (p) => {
  if (!p) return p
  let s = p.trim().replace(/\\/g, '/').replace(/\/+$/, '')
  if (s === '.' || s === './') return './'
  if (s && !s.startsWith('./') && !s.startsWith('/')) s = './' + s
  return s
}

const handleFolderPick = (pickedPath) => {
  const root = project.value?.rootDir || ''
  let rel = pickedPath
  const nRoot = root.replace(/\\/g, '/').replace(/\/+$/, '')
  const nPick = pickedPath.replace(/\\/g, '/').replace(/\/+$/, '')
  if (nPick.startsWith(nRoot)) rel = nPick.slice(nRoot.length).replace(/^\/+/, '')
  if (folderPickerTarget.value === 'add') addModuleForm.value.dir = normalizeDir(rel)
  else editForm.value.dir = normalizeDir(rel)
  showFolderPicker.value = false
}

const handleAddModule = async () => {
  if (!addModuleForm.value.dir) return
  addingModule.value = true
  try { await store.addModule(id, { name: addModuleForm.value.name, type: addModuleForm.value.type, dir: normalizeDir(addModuleForm.value.dir) }); await loadProject(); showAddModule.value = false; addModuleForm.value = { name: '前端', type: 'frontend', dir: '' }; toast.success('已添加') }
  catch (err) { toast.error(err.message) }
  finally { addingModule.value = false }
}

const handleRemoveModule = async (idx) => {
  const mod = project.value.modules[idx]
  if (!confirm(`确定移除 "${mod.name || `模块${idx + 1}`}"？`)) return
  try { await store.removeModule(id, idx); await loadProject(); toast.success('已移除') }
  catch (err) { toast.error(err.message) }
}

const startEditModule = async (idx) => {
  editingModule.value = idx
  const mod = project.value.modules[idx]
  editForm.value = { name: mod.name || '', dir: mod.dir || '', startupScript: '', port: mod.port || 0 }
  try {
    const r = await store.getModuleScript(id, idx)
    editForm.value.startupScript = r?.content || mod.startupScript || ''
  } catch {
    editForm.value.startupScript = mod.startupScript || ''
  }
}

const handleSaveModule = async () => {
  savingModule.value = true
  try { await store.updateModule(id, editingModule.value, { name: editForm.value.name, dir: normalizeDir(editForm.value.dir), startupScript: editForm.value.startupScript, port: editForm.value.port }); await loadProject(); editingModule.value = null; toast.success('已保存') }
  catch (err) { toast.error(err.message) }
  finally { savingModule.value = false }
}

const showLog = async (idx) => { logModalIndex.value = idx; showLogModal.value = true; await refreshLog() }

const refreshLog = async () => {
  try {
    if (logModalIndex.value !== null) {
      const r = await store.getModuleLog(id, logModalIndex.value)
      logModalContent.value = r?.output || ''
    }
  } catch (err) { logModalContent.value = `获取失败: ${err.message}` }
}

const handleScanModules = async () => {
  scanningModules.value = true
  try { const r = await store.scanModules(id); scannedModules.value = (r?.modules || []).map(m => ({ ...m, selected: true })); showScanModal.value = true }
  catch (err) { toast.error(err.message) }
  finally { scanningModules.value = false }
}

const handleAddScannedModules = async () => {
  addingScannedModules.value = true
  try {
    let added = 0
    for (const m of scannedModules.value.filter(m => m.selected)) { try { await store.addModule(id, { type: m.type, dir: m.dir, name: m.name }); added++ } catch {} }
    if (added > 0) { toast.success(`已添加 ${added} 个`); showScanModal.value = false; await loadProject() }
    else toast.info('未能添加')
  } catch (err) { toast.error(err.message) }
  finally { addingScannedModules.value = false }
}

const getCreationMethodLabel = (m) => ({ git: 'Git导入', scaffold: '框架', new: '新建' }[m] || m || '项目')

const getAccessLinks = (mod) => {
  const portLinks = (mod.ports || []).map(p => `http://localhost:${p}`)
  const logLinks = mod.links || []
  const all = [...portLinks, ...logLinks]
  const seen = new Set()
  const result = []
  for (const link of all) {
    try {
      const u = new URL(link)
      const key = `${u.hostname}:${u.port || (u.protocol === 'https:' ? '443' : '80')}`
      if (!seen.has(key)) { seen.add(key); result.push(link) }
    } catch {
      if (!seen.has(link)) { seen.add(link); result.push(link) }
    }
  }
  return result
}

const formatLink = (link) => {
  try {
    const u = new URL(link)
    const host = u.hostname
    const port = u.port || (u.protocol === 'https:' ? '443' : '80')
    const defaultPort = u.protocol === 'https:' ? '443' : '80'
    return port === defaultPort ? host : `${host}:${port}`
  } catch {
    const m = link.match(/^https?:\/\/([^/:]+)(?::(\d+))?/)
    if (m) {
      const port = m[2] || '80'
      return port === '80' ? m[1] : `${m[1]}:${port}`
    }
    return ''
  }
}
</script>

<style src="./ProjectDetail.css" scoped></style>
