import request from './request'

// 获取所有项目
export const getProjects = () => request.get('/projects')

// 获取单个项目
export const getProject = (id) => request.get(`/projects/${id}`)

// 创建项目
export const createProject = (data) => request.post('/projects', data)

// 创建通用项目（关联本地目录）
export const createGenericProject = (data) => request.post('/projects/generic', data)

// 从Git仓库导入项目
export const importProject = (data) => request.post('/projects/import', data)

// 更新项目（重命名）
export const updateProject = (id, data) => request.put(`/projects/${id}`, data)

// 更新项目的Git配置
export const updateProjectGit = (id, data) => request.put(`/projects/${id}/git`, data)

// 添加模块
export const addModule = (id, data) => request.post(`/projects/${id}/module`, data)

// 更新模块
export const updateModule = (id, moduleIndex, data) => request.put(`/projects/${id}/module/${moduleIndex}`, data)

// 移除模块
export const removeModule = (id, moduleIndex) => request.delete(`/projects/${id}/module/${moduleIndex}`)

// 拉取最新代码
export const pullProject = (id) => request.post(`/projects/${id}/pull`)

// 删除项目
export const deleteProject = (id) => request.delete(`/projects/${id}`)

// 启动项目（target: "all" 或模块索引）
export const startProject = (id, target = 'all') => request.post(`/projects/${id}/start`, { target })

// 停止项目（target: "all" 或模块索引）
export const stopProject = (id, target = 'all') => request.post(`/projects/${id}/stop`, { target })

// 同步状态
export const syncProjectStatus = (id) => request.post(`/projects/${id}/sync`)

// 获取模块运行日志
export const getModuleLog = (id, moduleIndex) => request.get(`/projects/${id}/log/${moduleIndex}`)

// 获取模块启动脚本内容
export const getModuleScript = (id, moduleIndex) => request.get(`/projects/${id}/module/${moduleIndex}/script`)

// 扫描项目子目录，自动发现模块
export const scanModules = (id) => request.post(`/projects/${id}/scan-modules`)
