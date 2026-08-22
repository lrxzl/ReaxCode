
<template>
  <div class="page">
    <header class="hd">
      <h1 class="hd-title">AIPM 项目管理</h1>
      <div class="hd-btns">
        <a v-if="projectsDirUrl" class="btn btn-outline btn-sm" style="text-decoration: none"
           :href="projectsDirUrl" target="_blank">📂 目录</a>
        <button class="btn btn-outline btn-sm" @click="showImportModal = true">导入</button>
        <button class="btn btn-primary btn-sm" @click="showCreateModal = true">+ 创建</button>
      </div>
    </header>

    <!-- 局域网提示 -->
    <div class="lan-hint" v-if="lanIps.length">
      局域网访问:
      <template v-for="(ip, i) in lanIps" :key="ip">
        <a :href="'http://' + ip + ':3456'" target="_blank">{{ ip }}:3456</a>
        <span v-if="i < lanIps.length - 1" class="lan-sep"> / </span>
      </template>
      <span class="lan-tip"> — 可从其他设备访问项目管理</span>
    </div>

    <div v-if="loading" class="loading"><div class="spinner"></div>加载中...</div>

    <div v-else-if="projects.length === 0" class="empty">
      <div class="empty-icon">📦</div>
      <div class="empty-hint">还没有项目，点击上方按钮创建</div>
    </div>

    <div v-else class="list">
      <div v-for="project in projects" :key="project.id" class="item">
        <div class="item-main" @click="$router.push(`/project/${project.id}`)">
          <div class="item-row1">
            <span class="item-name">{{ project.projectName }}</span>
            <span :class="['st', project.isRunning ? 'st-on' : 'st-off']">
              {{ project.isRunning ? '运行中' : '已停止' }}
            </span>
          </div>
          <div class="item-row2">
            <span>{{ getCreationMethodLabel(project.creationMethod) }}</span>
            <span class="sep">·</span>
            <span>{{ (project.modules || []).length }}个模块</span>
          </div>
          <div class="item-path" :title="project.rootDir">{{ project.rootDir }}</div>
        </div>

        <div class="item-ops">
          <button class="obtn" @click="$router.push(`/project/${project.id}/git`)">Git</button>
          <button class="obtn" @click="handleRename(project)">✎</button>
          <button class="obtn obtn-del" @click="handleDelete(project)">×</button>
        </div>
      </div>
    </div>

    <!-- 导入弹窗 -->
    <div v-if="showImportModal" class="modal-mask" @click.self="showImportModal = false">
      <div class="modal-box">
        <div class="modal-head">从Git导入</div>
        <div class="modal-body">
          <div class="field">
            <label>仓库地址 *</label>
            <input class="mono" v-model="importForm.url" placeholder="https://github.com/user/repo.git" />
          </div>
          <div class="field">
            <label>项目名称</label>
            <input v-model="importForm.name" placeholder="留空自动提取" />
          </div>
          <div class="field">
            <label>用户名</label>
            <input v-model="importForm.username" placeholder="可选" />
          </div>
          <div class="field">
            <label>密码</label>
            <input v-model="importForm.password" type="password" placeholder="可选" />
          </div>
        </div>
        <div class="modal-foot">
          <button class="btn btn-outline btn-sm" @click="showImportModal = false">取消</button>
          <button class="btn btn-primary btn-sm" :disabled="!importForm.url || importing" @click="handleImport">
            {{ importing ? '导入中...' : '导入' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 创建弹窗 -->
    <div v-if="showCreateModal" class="modal-mask" @click.self="showCreateModal = false">
      <div class="modal-box">
        <div class="modal-head">创建项目</div>
        <div class="modal-body">
          <div class="field">
            <label>创建方式</label>
            <div class="tabs">
              <button :class="['tab', { active: createMode === 'scaffold' }]" @click="createMode = 'scaffold'">框架</button>
              <button :class="['tab', { active: createMode === 'generic' }]" @click="createMode = 'generic'">通用</button>
            </div>
          </div>
          <div class="field">
            <label>项目名称 *</label>
            <input v-model="createForm.name" placeholder="输入项目名称" />
          </div>
          <template v-if="createMode === 'scaffold'">
            <div class="field">
              <label>技术栈</label>
              <div class="slist">
                <div v-for="s in scaffolds" :key="s.name"
                  :class="['slist-item', { active: createForm.scaffold === s.name }]"
                  @click="createForm.scaffold = s.name">
                  <span class="slist-name">{{ s.name }}</span>
                  <span class="slist-desc">{{ s.description }}</span>
                </div>
              </div>
            </div>
          </template>
          <template v-if="createMode === 'generic'">
            <div class="field">
              <label>项目目录（可选）</label>
              <div class="frow">
                <input v-model="createForm.dir" placeholder="留空则自动创建" />
                <button class="btn btn-outline btn-sm" @click="showDirPicker = true">选择</button>
              </div>
            </div>
          </template>
        </div>
        <div class="modal-foot">
          <button class="btn btn-outline btn-sm" @click="showCreateModal = false">取消</button>
          <button class="btn btn-primary btn-sm" :disabled="!createForm.name || creating" @click="handleCreate">
            {{ creating ? '创建中...' : '创建' }}
          </button>
        </div>
      </div>
    </div>

    <FolderPicker :visible="showDirPicker" title="选择项目目录" :initialPath="projectsDirUrl ? '' : ''" @confirm="(p) => { createForm.dir = p; showDirPicker = false }" @cancel="showDirPicker = false" />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import { useProjectStore } from '../stores/project'
import { useToast } from '../composables/useToast'
import { useStatusStream } from '../composables/useStatusStream'
import { getHomeDir, getLanIp } from '../api/files'
import FolderPicker from '../components/FolderPicker.vue'

const store = useProjectStore()
const router = useRouter()
const toast = useToast()
const { projects, scaffolds, loading } = storeToRefs(store)

const showCreateModal = ref(false)
const showImportModal = ref(false)
const creating = ref(false)
const importing = ref(false)
const createMode = ref('scaffold')

const createForm = ref({ name: '', scaffold: '', dir: '' })
const importForm = ref({ url: '', name: '', username: '', password: '' })
const showDirPicker = ref(false)

const getCreationMethodLabel = (m) => ({ git: 'Git导入', scaffold: '框架', new: '新建' }[m] || m || '项目')

const projectsDirUrl = ref('')
const lanIps = ref([])

onMounted(async () => {
  await Promise.all([store.fetchProjects(), store.fetchScaffolds()])
  if (scaffolds.value.length > 0) createForm.value.scaffold = scaffolds.value[0].name
  try { const res = await getHomeDir(); projectsDirUrl.value = `/files?path=${res.data + '/projects'}` } catch {}
  try { const res = await getLanIp(); lanIps.value = Array.isArray(res.data) ? res.data : res.data ? [res.data] : [] } catch {}
})

useStatusStream(() => {
  store.fetchProjects()
})

const handleCreate = async () => {
  if (!createForm.value.name) return
  creating.value = true
  try {
    if (createMode.value === 'generic') await store.createGenericProject({ name: createForm.value.name, dir: createForm.value.dir || undefined })
    else await store.createProject(createForm.value)
    toast.success('创建成功'); showCreateModal.value = false
    createForm.value = { name: '', scaffold: scaffolds.value[0]?.name || '', dir: '' }
  } catch (err) { toast.error(err.message) }
  finally { creating.value = false }
}

const handleImport = async () => {
  if (!importForm.value.url) return
  importing.value = true
  try {
    await store.importProject({ url: importForm.value.url, name: importForm.value.name || undefined, username: importForm.value.username || undefined, password: importForm.value.password || undefined })
    toast.success('导入成功'); showImportModal.value = false
    importForm.value = { url: '', name: '', username: '', password: '' }
  } catch (err) { toast.error(err.message) }
  finally { importing.value = false }
}

const handleRename = async (project) => {
  const newName = prompt('输入新名称:', project.projectName)
  if (!newName || newName === project.projectName) return
  try { await store.renameProject(project.id, newName); toast.success('重命名成功') }
  catch (err) { toast.error(err.message) }
}

const handleDelete = async (project) => {
  if (!confirm(`确定要移除 "${project.projectName}" 吗？`)) return
  try { await store.deleteProject(project.id); toast.success('已删除') }
  catch (err) { toast.error(err.message) }
}
</script>

<style src="./ProjectList.css" scoped></style>
