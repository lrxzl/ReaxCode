
<template>
  <div class="page">
    <header class="hd">
      <button class="btn-back" @click="$router.push(`/project/${projectId}`)">←</button>
      <h1 class="hd-title">Git版本</h1>
    </header>

    <!-- 远程仓库 -->
    <div class="info-bar" @click="showRemoteModal = true">
      <span class="itag" style="background:var(--primary-bg);color:var(--primary);border-color:rgba(129,140,248,0.2)">远程</span>
      <span v-if="remoteUrl" class="mono" style="font-size:11px;color:var(--text-secondary);flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ remoteUrl }}</span>
      <span v-else style="font-size:11px;color:var(--text-muted)">未配置</span>
      <button class="btn-copy">✎</button>
    </div>

    <!-- 分支 -->
    <div class="sec">
      <div class="tabs">
        <button :class="['tab', { active: branchTab === 'local' }]" @click="branchTab = 'local'">本地({{ localBranches.length }})</button>
        <button :class="['tab', { active: branchTab === 'remote' }]" @click="branchTab = 'remote'">远程({{ remoteBranches.length }})</button>
        <button class="btn btn-outline btn-sm" style="margin-left:auto" @click="showCreateBranchModal = true">新建</button>
      </div>
<!--      <div v-if="switchingBranch" style="font-size:11px;color:var(&#45;&#45;text-muted);padding:4px 0">切换中...</div>-->
      <div class="blist">
        <template v-if="branchTab === 'local'">
          <div v-for="b in localBranches" :key="b.name" class="bitem">
            <span :class="['bdot', b.current ? 'bdot-cur' : '']"></span>
            <span class="bname" @click="handleBranchSwitch(b.name)">{{ b.name }}</span>
            <span v-if="b.current" class="btag">当前</span>
            <button v-if="!b.current" class="btn btn-outline btn-xs" style="color:var(--danger);border-color:rgba(248,113,113,0.3);margin-left:auto" @click.stop="handleDeleteBranch(b.name)">删除</button>
          </div>
        </template>
        <template v-else>
          <div v-for="b in remoteBranches" :key="b.name" class="bitem">
            <span class="bdot bdot-remote"></span>
            <span class="bname">{{ b.name }}</span>
            <button v-if="!localBranches.some(l => l.name === b.name)" class="btn btn-outline btn-xs" @click="handleCreateLocalFromRemote(b.name)">拉取</button>
          </div>
          <div v-if="!remoteBranches.length"
               @click="showRemoteModal = true"
               style="text-align:center;padding:10px;font-size:11px;color:var(--text-muted)">暂无远程分支，去 <span style="color: #4f46e5;">配置 ✎</span></div>
        </template>
      </div>
    </div>

    <!-- 状态 -->
    <div v-if="status" class="sec">
      <div class="status-bar">
        <span :class="['sdot', status.clean ? 'sdot-ok' : 'sdot-mod']"></span>
        <span style="font-size:12px;color:var(--text-secondary)">{{ status.clean ? '已提交' : '有更改' }}</span>
        <button class="btn btn-primary btn-sm" :disabled="status.clean || committing" @click="showCommitModal = true">
          {{ committing ? '...' : '提交保存' }}
        </button>
      </div>
      <div v-if="!status.clean && status.files.length" class="flist">
        <div v-for="file in status.files" :key="file.file" class="frow">
          <span :class="['fflag', file.status]">{{ file.flag }}</span>
<span class="fname" @click="handleShowFileDiff(file.file)" :title="file.file">{{ getDisplayPath(file.file) }}</span>
          <div class="factions">
            <button class="btn btn-outline btn-xs" @click.stop="handleCommitFile(file.file)">提交</button>
            <button class="btn btn-outline btn-xs" style="color:var(--warning);border-color:rgba(251,191,36,0.3)" @click.stop="handleRestoreFile(file.file)">撤回</button>
          </div>
        </div>
      </div>
      <div v-if="!status.clean" style="display:flex;gap:5px;margin-top:6px">
        <button class="btn btn-outline btn-sm" :disabled="viewingAllDiff" @click="handleShowAllDiff">{{ viewingAllDiff ? '...' : '全部差异' }}</button>
        <button class="btn btn-outline btn-sm" style="color:var(--danger);border-color:rgba(248,113,113,0.3)" @click="handleDiscard">丢弃更改</button>
      </div>
    </div>

    <!-- 合并冲突提示 -->
    <div v-if="mergeConflict" class="sec" style="background:rgba(248,113,113,0.08);border:1px solid rgba(248,113,113,0.2);border-radius:8px">
      <div style="font-size:12px;font-weight:500;color:var(--danger);margin-bottom:6px">合并冲突</div>
      <div v-if="conflictFiles.length" style="font-size:11px;color:var(--text-secondary);margin-bottom:8px">
        冲突文件：
        <div v-for="f in conflictFiles" :key="f" class="mono" style="padding:2px 0">{{ f }}</div>
      </div>
      <div style="font-size:11px;color:var(--text-muted);margin-bottom:8px">请在编辑器中解决冲突标记（<<<<<<< ======= >>>>>>>），然后提交或中止合并。</div>
      <button class="btn btn-outline btn-sm" style="color:var(--danger);border-color:rgba(248,113,113,0.3)" @click="handleMergeAbort">中止合并</button>
    </div>

    <div class="sec">
    <div class="ops">
      <button class="opbtn" :disabled="pulling" @click="showPullModal = true">↓ {{ pulling ? '...' : '拉取' }}</button>
      <button class="opbtn" :disabled="pushing" @click="handlePush">↑ {{ pushing ? '...' : '推送' }}</button>
      <button class="opbtn" :disabled="loading" @click="showMergeModal = true">⑂ 合并</button>
      <button class="opbtn" :disabled="loading" @click="loadAll(true)">↻ 刷新</button>
    </div>

    <div v-if="loading" class="loading"><div class="spinner"></div>加载中...</div>

    <div v-else-if="!history.length" class="empty">
      <div class="empty-icon">📭</div>
      <div style="font-size:13px;color:var(--text-muted)">还没有提交记录</div>
    </div>

    <div v-else class="clist">
      <div v-for="(commit, index) in history" :key="commit.hash" class="citem">
        <div class="ctop">
          <span class="cmsg">{{ commit.message }}</span>
          <span class="chash">{{ commit.hash.substring(0, 7) }}</span>
        </div>
        <div class="cdate">{{ formatDate(commit.date) }}</div>
        <div class="cops">
          <button class="btn btn-outline btn-xs" :disabled="restoringHash === commit.hash" @click="handleRestore(commit)">{{ restoringHash === commit.hash ? '...' : '恢复' }}</button>
          <button class="btn btn-outline btn-xs" @click="handleShowDetail(commit)">详情</button>
          <button v-if="index < history.length - 1" class="btn btn-outline btn-xs" @click="handleDiff(commit, history[index + 1])">差异</button>
        </div>
      </div>
    </div>

    </div>

    <!-- 提交弹窗 -->
    <div v-if="showCommitModal" class="modal-mask" @click.self="showCommitModal = false">
      <div class="modal-box">
        <div class="modal-head">提交保存</div>
        <div class="modal-body"><div class="field"><label>提交说明</label><input v-model="commitMessage" placeholder="描述修改..." @keyup.enter="handleCommit" /></div></div>
        <div class="modal-foot">
          <button class="btn btn-outline btn-sm" @click="showCommitModal = false">取消</button>
          <button class="btn btn-primary btn-sm" :disabled="!commitMessage || committing" @click="handleCommit">{{ committing ? '...' : '提交' }}</button>
        </div>
      </div>
    </div>

    <!-- 详情弹窗 -->
    <div v-if="showDetailModal" class="modal-mask" @click.self="showDetailModal = false">
      <div class="modal-box">
        <div class="modal-head">提交详情</div>
        <div class="modal-body logbox"><pre v-if="detailContent">{{ detailContent }}</pre></div>
        <div class="modal-foot"><button class="btn btn-outline btn-sm" @click="showDetailModal = false">关闭</button></div>
      </div>
    </div>

    <!-- 差异弹窗 -->
    <div v-if="showDiffModal" class="modal-mask" @click.self="showDiffModal = false">
      <div class="modal-box">
        <div class="modal-head">文件差异</div>
        <div class="modal-body logbox">
          <pre v-if="diffContent" class="diff-pre" v-html="renderDiff(diffContent)"></pre>
          <div v-else style="text-align:center;padding:12px;color:var(--text-muted);font-size:12px">没有差异</div>
        </div>
        <div class="modal-foot"><button class="btn btn-outline btn-sm" @click="showDiffModal = false">关闭</button></div>
      </div>
    </div>

    <!-- 新建分支 -->
    <div v-if="showCreateBranchModal" class="modal-mask" @click.self="showCreateBranchModal = false">
      <div class="modal-box">
        <div class="modal-head">新建分支</div>
        <div class="modal-body"><div class="field"><label>分支名称</label><input class="mono" v-model="newBranchName" placeholder="feature/xxx" @keyup.enter="handleCreateBranch" /></div></div>
        <div class="modal-foot">
          <button class="btn btn-outline btn-sm" @click="showCreateBranchModal = false">取消</button>
          <button class="btn btn-primary btn-sm" :disabled="!newBranchName || creatingBranch" @click="handleCreateBranch">{{ creatingBranch ? '...' : '创建' }}</button>
        </div>
      </div>
    </div>

    <!-- 远程仓库配置 -->
    <div v-if="showRemoteModal" class="modal-mask" @click.self="showRemoteModal = false">
      <div class="modal-box">
        <div class="modal-head">
          <div class="tabs" style="border:none;margin:0">
            <button :class="['tab', { active: remoteTab === 'config' }]" @click="remoteTab = 'config'">配置仓库</button>
            <button :class="['tab', { active: remoteTab === 'create' }]" @click="remoteTab = 'create'">创建仓库</button>
          </div>
        </div>
        <div class="modal-body">
          <!-- 配置已有仓库 -->
          <template v-if="remoteTab === 'config'">
            <div style="font-size:11px;color:var(--text-muted);margin-bottom:10px">配置已有远程仓库的地址和认证信息，用于拉取和推送代码。</div>
            <div class="field">
              <label>仓库地址</label>
              <div class="frow">
                <input class="mono" v-model="remoteUrlInput" placeholder="https://gitee.com/user/repo.git" />
                <button class="btn btn-outline btn-sm" @click="showHistory('url')" style="flex-shrink:0">历史</button>
              </div>
              <div v-if="historyType === 'url'" class="history-list">
                <div v-for="(item, i) in historyList" :key="i" class="history-item" @click="remoteUrlInput = item; historyType = ''">
                  <span class="mono" style="flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ item }}</span>
                  <button class="btn-copy" @click.stop="removeHistory('url', i)">✕</button>
                </div>
                <div v-if="historyList.length === 0" class="history-empty">暂无历史</div>
              </div>
            </div>
            <div class="field">
              <label>私人令牌 <span style="color:var(--text-muted);font-weight:400">（公开仓库可留空）</span></label>
              <div class="frow">
                <input v-model="remoteTokenInput" :type="showToken ? 'text' : 'password'" placeholder="需要私人令牌，非登录密码" />
                <button class="btn btn-outline btn-sm" @click="showToken = !showToken" style="flex-shrink:0">{{ showToken ? '隐藏' : '显示' }}</button>
                <button class="btn btn-outline btn-sm" @click="showHistory('token')" style="flex-shrink:0">历史</button>
              </div>
              <div v-if="historyType === 'token'" class="history-list">
                <div v-for="(item, i) in historyList" :key="i" class="history-item" @click="remoteTokenInput = item; historyType = ''">
                  <span class="mono" style="flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ item.slice(0, 8) }}****</span>
                  <button class="btn-copy" @click.stop="removeHistory('token', i)">✕</button>
                </div>
                <div v-if="historyList.length === 0" class="history-empty">暂无历史</div>
              </div>
            </div>
          </template>
          <!-- 创建新仓库 -->
          <template v-else>
            <div style="font-size:11px;color:var(--text-muted);margin-bottom:10px">在远程平台创建一个新的空仓库，并自动配置为当前项目的远程地址。</div>
            <div class="field">
              <label>仓库地址</label>
              <div class="frow">
                <input class="mono" v-model="remoteUrlInput" placeholder="https://gitee.com/user/repo.git" />
                <button class="btn btn-outline btn-sm" @click="showHistory('url')" style="flex-shrink:0">历史</button>
              </div>
              <div v-if="historyType === 'url'" class="history-list">
                <div v-for="(item, i) in historyList" :key="i" class="history-item" @click="remoteUrlInput = item; historyType = ''">
                  <span class="mono" style="flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ item }}</span>
                  <button class="btn-copy" @click.stop="removeHistory('url', i)">✕</button>
                </div>
                <div v-if="historyList.length === 0" class="history-empty">暂无历史</div>
              </div>
            </div>
            <div class="field">
              <label>私人令牌 <span style="color:var(--danger);font-weight:400">*必填</span></label>
              <div class="frow">
                <input v-model="remoteTokenInput" :type="showToken ? 'text' : 'password'" placeholder="需要私人令牌，非登录密码" />
                <button class="btn btn-outline btn-sm" @click="showToken = !showToken" style="flex-shrink:0">{{ showToken ? '隐藏' : '显示' }}</button>
                <button class="btn btn-outline btn-sm" @click="showHistory('token')" style="flex-shrink:0">历史</button>
              </div>
              <div v-if="historyType === 'token'" class="history-list">
                <div v-for="(item, i) in historyList" :key="i" class="history-item" @click="remoteTokenInput = item; historyType = ''">
                  <span class="mono" style="flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ item.slice(0, 8) }}****</span>
                  <button class="btn-copy" @click.stop="removeHistory('token', i)">✕</button>
                </div>
                <div v-if="historyList.length === 0" class="history-empty">暂无历史</div>
              </div>
            </div>
          </template>
          <div style="font-size:11px;color:var(--text-muted)">
            GitHub: Settings → Developer settings → Tokens；<br>
            Gitee: 个人设置 → 私人令牌
          </div>
        </div>
        <div class="modal-foot">
          <button class="btn btn-outline btn-sm" @click="showRemoteModal = false">取消</button>
          <template v-if="remoteTab === 'config'">
            <button class="btn btn-primary btn-sm" :disabled="settingRemote || !remoteUrlInput" @click="handleSetRemote">{{ settingRemote ? '...' : '保存' }}</button>
          </template>
          <template v-else>
            <button class="btn btn-primary btn-sm" :disabled="creatingRepo || !remoteUrlInput || !remoteTokenInput" @click="handleCreateRepo">
              {{ creatingRepo ? '创建中...' : '创建并配置' }}
            </button>
          </template>
        </div>
      </div>
    </div>

    <!-- 拉取选项 -->
    <div v-if="showPullModal" class="modal-mask" @click.self="showPullModal = false">
      <div class="modal-box">
        <div class="modal-head">拉取更新</div>
        <div class="modal-body">
          <div class="pull-list">
            <div class="pull-item" @click="handlePull('rebase')"><span style="font-weight:500;font-size:12px;color:var(--text-primary)">Rebase</span><span style="font-size:10px;color:var(--text-muted)">线性历史</span></div>
            <div class="pull-item" @click="handlePull('merge')"><span style="font-weight:500;font-size:12px;color:var(--text-primary)">Merge</span><span style="font-size:10px;color:var(--text-muted)">合并提交</span></div>
          </div>
        </div>
        <div class="modal-foot"><button class="btn btn-outline btn-sm" @click="showPullModal = false">取消</button></div>
      </div>
    </div>

    <!-- 合并分支 -->
    <div v-if="showMergeModal" class="modal-mask" @click.self="showMergeModal = false">
      <div class="modal-box">
        <div class="modal-head">合并分支</div>
        <div class="modal-body">
          <div class="field">
            <label>合并到 [{{ currentBranch }}]</label>
            <select v-model="mergeSourceBranch">
              <option value="">选择分支</option>
              <optgroup label="本地"><option v-for="b in localBranches" :key="b.name" :value="b.name" :disabled="b.current">{{ b.name }}</option></optgroup>
              <optgroup label="远程"><option v-for="b in remoteBranches" :key="b.name" :value="`origin/${b.name}`">origin/{{ b.name }}</option></optgroup>
            </select>
          </div>
        </div>
        <div class="modal-foot">
          <button class="btn btn-outline btn-sm" @click="showMergeModal = false">取消</button>
          <button class="btn btn-primary btn-sm" :disabled="!mergeSourceBranch || merging" @click="handleMerge">{{ merging ? '...' : '合并' }}</button>
        </div>
      </div>
    </div>

    <!-- 文件差异 -->
    <div v-if="showFileDiffModal" class="modal-mask" @click.self="showFileDiffModal = false">
      <div class="modal-box">
        <div class="modal-head" :title="currentFileName">{{ currentFileName }}</div>
        <div class="modal-body logbox">
          <pre v-if="currentFileDiff" class="diff-pre" v-html="renderDiff(currentFileDiff)"></pre>
          <div v-else style="text-align:center;padding:12px;color:var(--text-muted);font-size:12px">没有差异</div>
        </div>
        <div class="modal-foot"><button class="btn btn-outline btn-sm" @click="showFileDiffModal = false">关闭</button></div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useToast } from '../composables/useToast'
import * as gitApi from '../api/git'
import { commitFile as commitFileApi, restoreFile as restoreFileApi, mergeAbort as mergeAbortApi } from '../api/git'
import { createRemoteRepo } from '../api/git'
import { updateProjectGit } from '../api/projects'

const route = useRoute()
const toast = useToast()
const projectId = route.params.id
const loading = ref(false)
const status = ref(null)
const history = ref([])
const restoringHash = ref(null)

const showCommitModal = ref(false)
const commitMessage = ref('')
const committing = ref(false)
const showDetailModal = ref(false)
const detailContent = ref('')
const showDiffModal = ref(false)
const diffContent = ref('')
const branches = ref([])
const localBranches = ref([])
const remoteBranches = ref([])
const currentBranch = ref('')
const switchingBranch = ref(false)
const showCreateBranchModal = ref(false)
const newBranchName = ref('')
const creatingBranch = ref(false)
const branchTab = ref('local')
const showPullModal = ref(false)
const showMergeModal = ref(false)
const mergeSourceBranch = ref('')
const merging = ref(false)
const mergeConflict = ref(false)
const conflictFiles = ref([])
const remoteUrl = ref('')
const showRemoteModal = ref(false)
const remoteTab = ref('config')
const remoteUrlInput = ref('')
const remoteTokenInput = ref('')
const settingRemote = ref(false)
const pushing = ref(false)
const showToken = ref(false)
const creatingRepo = ref(false)
const historyType = ref('')
const historyList = ref([])

const HISTORY_URL_KEY = 'aipm_git_urls'
const HISTORY_TOKEN_KEY = 'aipm_git_tokens'

const getStoredHistory = (key) => {
  try { return JSON.parse(localStorage.getItem(key) || '[]') } catch { return [] }
}
const saveHistory = (key, value) => {
  if (!value) return
  const list = getStoredHistory(key).filter(v => v !== value)
  list.unshift(value)
  if (list.length > 10) list.length = 10
  localStorage.setItem(key, JSON.stringify(list))
}
const showHistory = (type) => {
  if (historyType.value === type) { historyType.value = ''; return }
  historyType.value = type
  historyList.value = getStoredHistory(type === 'url' ? HISTORY_URL_KEY : HISTORY_TOKEN_KEY)
}
const removeHistory = (type, index) => {
  const key = type === 'url' ? HISTORY_URL_KEY : HISTORY_TOKEN_KEY
  const list = getStoredHistory(key)
  list.splice(index, 1)
  localStorage.setItem(key, JSON.stringify(list))
  historyList.value = list
}
const pulling = ref(false)
const viewingAllDiff = ref(false)
const currentFileDiff = ref('')
const showFileDiffModal = ref(false)
const currentFileName = ref('')

const renderDiff = (d) => {
  if (!d) return ''
  return d.split('\n').map(l => {
    if (l.startsWith('+') && !l.startsWith('+++')) return `<span class="diff-add">${esc(l)}</span>`
    if (l.startsWith('-') && !l.startsWith('---')) return `<span class="diff-del">${esc(l)}</span>`
    if (l.startsWith('@@')) return `<span class="diff-hunk">${esc(l)}</span>`
    return esc(l)
  }).join('\n')
}
const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

const getDisplayPath = (path) => { const parts = path.split('/'); if (parts.length >= 2) { return '\u202A../' + parts.slice(-2).join('/') + '\u202C'; } return '\u202A..' + parts[0] + '\u202C'; }
const loadStatus = async () => { try { const r = await gitApi.getGitStatus(projectId); status.value = r.data } catch {} }
const loadHistory = async () => { loading.value = true; try { const r = await gitApi.getGitHistory(projectId); history.value = r.data || [] } catch (e) { toast.error('获取历史失败') } finally { loading.value = false } }
const loadBranches = async () => {
  try {
    const [a, l, rm] = await Promise.all([gitApi.getBranches(projectId), gitApi.getLocalBranches(projectId), gitApi.getRemoteBranches(projectId)])
    branches.value = a.data || []; localBranches.value = l.data || []; remoteBranches.value = rm.data || []
    const c = branches.value.find(b => b.current); if (c) currentBranch.value = c.name
  } catch {}
}
const loadRemoteUrl = async () => { try { const r = await gitApi.getRemoteUrl(projectId); remoteUrl.value = r.data?.url || '' } catch {} }
const loadAll = async (withFetch = false) => {
  if (withFetch) {
    try { await gitApi.fetchRemote(projectId) } catch (e) { toast.error('刷新远程失败: ' + e.message) }
  }
  await Promise.all([loadStatus(), loadHistory(), loadBranches(), loadRemoteUrl()])
}

const handleShowAllDiff = async () => {
  viewingAllDiff.value = true
  try { const r = await gitApi.getDiffWorking(projectId); currentFileDiff.value = r.data?.diff || ''; currentFileName.value = '(全部变更)'; showFileDiffModal.value = true }
  catch (e) { toast.error('获取差异失败') }
  finally { viewingAllDiff.value = false }
}
const handleShowFileDiff = async (fn) => {
  try { const r = await gitApi.getDiffFile(projectId, fn); currentFileDiff.value = r.data?.diff || ''; currentFileName.value = fn; showFileDiffModal.value = true }
  catch (e) { toast.error('获取差异失败') }
}
const handleSetRemote = async () => {
  if (!remoteUrlInput.value) return; settingRemote.value = true
  try {
    saveHistory(HISTORY_URL_KEY, remoteUrlInput.value)
    if (remoteTokenInput.value) saveHistory(HISTORY_TOKEN_KEY, remoteTokenInput.value)
    await gitApi.setRemoteUrl(projectId, remoteUrlInput.value)
    const patch = { url: remoteUrlInput.value }
    if (remoteTokenInput.value) {
      patch.password = remoteTokenInput.value
      try { patch.username = new URL(remoteUrlInput.value).pathname.split('/')[1] || '' } catch {}
    }
    await updateProjectGit(projectId, patch)
    toast.success('配置成功')
    showRemoteModal.value = false
    await loadRemoteUrl()
  } catch (e) { toast.error('配置失败: ' + e.message) }
  finally { settingRemote.value = false }
}

const handleCreateRepo = async () => {
  if (!remoteUrlInput.value || !remoteTokenInput.value) return
  creatingRepo.value = true
  try {
    saveHistory(HISTORY_URL_KEY, remoteUrlInput.value)
    saveHistory(HISTORY_TOKEN_KEY, remoteTokenInput.value)
    await createRemoteRepo(projectId, { url: remoteUrlInput.value, password: remoteTokenInput.value })
    toast.success('仓库创建成功')
    showRemoteModal.value = false
    await loadRemoteUrl()
    try {
      await gitApi.pushToRemote(projectId, { setUpstream: true })
      toast.success('代码已推送到远程')
    } catch {}
    await loadAll(true)
  } catch (err) { toast.error(err.message) }
  finally { creatingRepo.value = false }
}
const handlePush = async () => {
  if (!remoteUrl.value) { toast.error('请先配置远程仓库'); showRemoteModal.value = true; return }
  if (!confirm('确定推送到远程？')) return; pushing.value = true
  try { const r = await gitApi.pushToRemote(projectId); toast.success(r.data?.message || '推送成功'); await loadAll(true) }
  catch (e) {
    const m = e.message || ''
    if (m.includes('upstream') || m.includes('does not match')) { if (confirm('创建上游分支并推送？')) { try { const r = await gitApi.pushToRemote(projectId, { setUpstream: true }); toast.success(r.data?.message || '成功'); await loadAll(true) } catch (e2) { toast.error('失败: ' + e2.message) } } }
    else if (m.includes('rejected') || m.includes('non-fast-forward')) { if (confirm('强制推送？')) { try { const r = await gitApi.pushToRemote(projectId, { force: true }); toast.success(r.data?.message || '成功'); await loadAll(true) } catch (e2) { toast.error('失败: ' + e2.message) } } }
    else toast.error('推送失败: ' + m)
  }
  finally { pushing.value = false }
}
const handleBranchSwitch = async (bn) => {
  const t = bn || currentBranch.value; if (!t) return; switchingBranch.value = true
  try { await gitApi.checkoutBranch(projectId, t); toast.success(`已切换: ${t}`); await Promise.all([loadStatus(), loadHistory(), loadBranches()]) }
  catch (e) { toast.error('切换失败'); await loadBranches() }
  finally { switchingBranch.value = false }
}
const handleDeleteBranch = async (name) => {
  if (!confirm(`确定删除分支 "${name}"？`)) return
  try { await gitApi.deleteBranch(projectId, name); toast.success('已删除'); await loadBranches() }
  catch (e) { toast.error('删除失败: ' + e.message) }
}
const handleCreateLocalFromRemote = async (n) => {
  switchingBranch.value = true
  try { await gitApi.trackBranch(projectId, `origin/${n}`); toast.success(`已拉取: ${n}`); await Promise.all([loadStatus(), loadHistory(), loadBranches()]) }
  catch (e) { toast.error('拉取失败: ' + e.message); await loadBranches() }
  finally { switchingBranch.value = false }
}
const handlePull = async (s) => {
  if (!remoteUrl.value) { toast.error('请先配置远程仓库'); showRemoteModal.value = true; showPullModal.value = false; return }
  showPullModal.value = false; if (!confirm(`确定拉取？(${s})`)) return; pulling.value = true
  try { const r = await gitApi.pullFromRemote(projectId, { strategy: s }); toast.success(r.data?.message || '拉取成功'); await loadAll() }
  catch (e) { toast.error('拉取失败: ' + e.message) }
  finally { pulling.value = false }
}
const handleMerge = async () => {
  if (!mergeSourceBranch.value) return; merging.value = true
  try {
    const r = await gitApi.mergeBranch(projectId, mergeSourceBranch.value)
    if (r.data && r.data.conflict) {
      mergeConflict.value = true
      conflictFiles.value = r.data.conflictFiles || []
      toast.error('合并冲突，请解决冲突后中止或继续')
      showMergeModal.value = false
    } else {
      toast.success(`已合并`)
      showMergeModal.value = false
      mergeSourceBranch.value = ''
      mergeConflict.value = false
      conflictFiles.value = []
      await Promise.all([loadStatus(), loadHistory(), loadBranches()])
    }
  }
  catch (e) { toast.error('合并失败: ' + e.message) }
  finally { merging.value = false }
}
const handleMergeAbort = async () => {
  try {
    await mergeAbortApi(projectId)
    toast.success('已中止合并')
    mergeConflict.value = false
    conflictFiles.value = []
    await Promise.all([loadStatus(), loadHistory(), loadBranches()])
  } catch (e) { toast.error('中止失败: ' + e.message) }
}
const handleCreateBranch = async () => {
  if (!newBranchName.value) return; creatingBranch.value = true
  try { await gitApi.createBranch(projectId, newBranchName.value); toast.success(`已创建`); showCreateBranchModal.value = false; newBranchName.value = ''; await loadBranches() }
  catch (e) { toast.error('创建失败') }
  finally { creatingBranch.value = false }
}
watch(showRemoteModal, (v) => { if (v) { remoteUrlInput.value = remoteUrl.value; remoteTab.value = 'config' } })
onMounted(loadAll)
const handleCommit = async () => {
  committing.value = true
  try { const r = await gitApi.commitSnapshot(projectId, commitMessage.value); toast.success(r.data.message || '提交成功'); showCommitModal.value = false; commitMessage.value = ''; await Promise.all([loadStatus(), loadHistory(), loadBranches()]) }
  catch (e) { toast.error('提交失败') }
  finally { committing.value = false }
}

const handleCommitFile = async (filePath) => {
  try {
    const r = await commitFileApi(projectId, filePath)
    toast.success(r.data.message || '提交成功')
    await Promise.all([loadStatus(), loadHistory(), loadBranches()])
  } catch (e) {
    toast.error('提交失败: ' + e.message)
  }
}

const handleRestoreFile = async (filePath) => {
  if (!confirm(`确定撤回 "${filePath}" 的更改？`)) return
  try {
    const r = await restoreFileApi(projectId, filePath)
    toast.success(r.data.message || '撤回成功')
    await Promise.all([loadStatus(), loadHistory(), loadBranches()])
  } catch (e) {
    toast.error('撤回失败: ' + e.message)
  }
}
const handleRestore = async (c) => {
  if (!confirm(`确定恢复到 "${c.message}"？`)) return; restoringHash.value = c.hash
  try { await gitApi.restoreSnapshot(projectId, c.hash); toast.success('已恢复'); await Promise.all([loadStatus(), loadHistory(), loadBranches()]) }
  catch (e) { toast.error('恢复失败') }
  finally { restoringHash.value = null }
}
const handleShowDetail = async (c) => { try { const r = await gitApi.showSnapshot(projectId, c.hash); detailContent.value = r.data.detail; showDetailModal.value = true } catch (e) { toast.error('获取失败') } }
const handleDiff = async (c1, c2) => { try { const r = await gitApi.getDiff(projectId, c2.hash, c1.hash); diffContent.value = r.data.diff; showDiffModal.value = true } catch (e) { toast.error('获取失败') } }
const handleDiscard = async () => { if (!confirm('确定丢弃所有更改？')) return; try { await gitApi.discardChanges(projectId); toast.success('已丢弃'); await loadStatus() } catch (e) { toast.error('丢弃失败') } }
const formatDate = (d) => { if (!d) return ''; const dt = new Date(d); return dt.toLocaleDateString('zh-CN') + ' ' + dt.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }
</script>

<style src="./GitHistory.css" scoped></style>
