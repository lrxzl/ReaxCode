import request from './request'

// 获取 git 状态
export const getGitStatus = (projectIndex) => request.get(`/git/${projectIndex}/status`)

// 获取提交历史
export const getGitHistory = (projectIndex, limit = 50) => request.get(`/git/${projectIndex}/history`, { params: { limit } })

// 提交（保存快照）
export const commitSnapshot = (projectIndex, message) => request.post(`/git/${projectIndex}/commit`, { message })

// 提交单个文件
export const commitFile = (projectIndex, file, message) => request.post(`/git/${projectIndex}/commit-file`, { file, message })

// 恢复到指定提交
export const restoreSnapshot = (projectIndex, hash) => request.post(`/git/${projectIndex}/restore/${hash}`)

// 撤回单个文件的更改
export const restoreFile = (projectIndex, file) => request.post(`/git/${projectIndex}/restore-file`, { file })

// 查看提交详情
export const showSnapshot = (projectIndex, hash) => request.get(`/git/${projectIndex}/show/${hash}`)

// 查看差异
export const getDiff = (projectIndex, hash1, hash2) => request.get(`/git/${projectIndex}/diff`, { params: { hash1, hash2 } })

// 查看工作区未提交的差异
export const getDiffWorking = (projectIndex) => request.get(`/git/${projectIndex}/diff-working`)

// 查看单个文件的差异
export const getDiffFile = (projectIndex, file) => request.get(`/git/${projectIndex}/diff-file`, { params: { file } })

// 丢弃所有未提交更改
export const discardChanges = (projectIndex) => request.post(`/git/${projectIndex}/discard`)

// 获取所有分支
export const getBranches = (projectIndex) => request.get(`/git/${projectIndex}/branches`)

// 获取当前分支
export const getCurrentBranch = (projectIndex) => request.get(`/git/${projectIndex}/branch/current`)

// 切换分支
export const checkoutBranch = (projectIndex, branch) => request.post(`/git/${projectIndex}/checkout`, { branch })

// 创建新分支
export const createBranch = (projectIndex, name) => request.post(`/git/${projectIndex}/branch/create`, { name })

// 从远程分支创建本地跟踪分支
export const trackBranch = (projectIndex, remoteBranch) => request.post(`/git/${projectIndex}/branch/track`, { remoteBranch })

// 删除分支
export const deleteBranch = (projectIndex, name) => request.delete(`/git/${projectIndex}/branch/${name}`)

// 推送到远程
export const pushToRemote = (projectIndex, options = {}) => request.post(`/git/${projectIndex}/push`, options)

// 从远程拉取更新
export const pullFromRemote = (projectIndex, options = {}) => request.post(`/git/${projectIndex}/pull`, options)

// 从远程获取最新分支信息
export const fetchRemote = (projectIndex) => request.post(`/git/${projectIndex}/fetch`)

// 获取本地分支
export const getLocalBranches = (projectIndex) => request.get(`/git/${projectIndex}/local-branches`)

// 获取远程分支
export const getRemoteBranches = (projectIndex) => request.get(`/git/${projectIndex}/remote-branches`)

// 合并分支
export const mergeBranch = (projectIndex, branch) => request.post(`/git/${projectIndex}/merge`, { branch })

// 中止合并
export const mergeAbort = (projectIndex) => request.post(`/git/${projectIndex}/merge-abort`)

// 获取远程仓库地址
export const getRemoteUrl = (projectIndex) => request.get(`/git/${projectIndex}/remote`)

// 设置远程仓库地址
export const setRemoteUrl = (projectIndex, url) => request.post(`/git/${projectIndex}/remote`, { url })

// 创建远程仓库
export const createRemoteRepo = (projectIndex, data) => request.post(`/git/${projectIndex}/create-repo`, data)
