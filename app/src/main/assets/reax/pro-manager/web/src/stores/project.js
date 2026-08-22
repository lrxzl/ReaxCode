import { ref } from 'vue'
import { defineStore } from 'pinia'
import * as projectsApi from '../api/projects'
import * as scaffoldsApi from '../api/scaffolds'

export const useProjectStore = defineStore('project', () => {
  const projects = ref([])
  const scaffolds = ref([])
  const loading = ref(false)
  const currentProject = ref(null)

  const fetchProjects = async () => {
    loading.value = true
    try {
      const res = await projectsApi.getProjects()
      projects.value = res.data || []
    } catch (err) {
      console.error('获取项目列表失败:', err)
      throw err
    } finally {
      loading.value = false
    }
  }

  const fetchScaffolds = async () => {
    try {
      const res = await scaffoldsApi.getScaffolds()
      scaffolds.value = res.data || []
    } catch (err) {
      console.error('获取手脚架列表失败:', err)
    }
  }

  const fetchProject = async (id) => {
    try {
      const res = await projectsApi.getProject(id)
      currentProject.value = res.data
      return res.data
    } catch (err) {
      console.error('获取项目详情失败:', err)
      throw err
    }
  }

  const createProject = async (data) => {
    try {
      const res = await projectsApi.createProject(data)
      await fetchProjects()
      return res.data
    } catch (err) {
      console.error('创建项目失败:', err)
      throw err
    }
  }

  const createGenericProject = async (data) => {
    try {
      const res = await projectsApi.createGenericProject(data)
      await fetchProjects()
      return res.data
    } catch (err) {
      console.error('创建通用项目失败:', err)
      throw err
    }
  }

  const importProject = async (data) => {
    try {
      const res = await projectsApi.importProject(data)
      await fetchProjects()
      return res.data
    } catch (err) {
      console.error('导入项目失败:', err)
      throw err
    }
  }

  const updateProjectGit = async (id, data) => {
    try {
      const res = await projectsApi.updateProjectGit(id, data)
      return res.data
    } catch (err) {
      console.error('更新Git配置失败:', err)
      throw err
    }
  }

  const addModule = async (id, data) => {
    try {
      const res = await projectsApi.addModule(id, data)
      await fetchProjects()
      return res.data
    } catch (err) {
      console.error('添加模块失败:', err)
      throw err
    }
  }

  const updateModule = async (id, moduleIndex, data) => {
    try {
      const res = await projectsApi.updateModule(id, moduleIndex, data)
      await fetchProjects()
      return res.data
    } catch (err) {
      console.error('更新模块失败:', err)
      throw err
    }
  }

  const removeModule = async (id, moduleIndex) => {
    try {
      const res = await projectsApi.removeModule(id, moduleIndex)
      await fetchProjects()
      return res.data
    } catch (err) {
      console.error('移除模块失败:', err)
      throw err
    }
  }

  const pullProject = async (id) => {
    try {
      const res = await projectsApi.pullProject(id)
      return res.data
    } catch (err) {
      console.error('拉取代码失败:', err)
      throw err
    }
  }

  const renameProject = async (id, name) => {
    try {
      const res = await projectsApi.updateProject(id, { name })
      await fetchProjects()
      return res.data
    } catch (err) {
      console.error('重命名失败:', err)
      throw err
    }
  }

  const deleteProject = async (id) => {
    try {
      await projectsApi.deleteProject(id)
      await fetchProjects()
    } catch (err) {
      console.error('删除项目失败:', err)
      throw err
    }
  }

  const startProject = async (id, target) => {
    try {
      const res = await projectsApi.startProject(id, target)
      return res.data
    } catch (err) {
      console.error('启动项目失败:', err)
      throw err
    }
  }

  const stopProject = async (id, target) => {
    try {
      const res = await projectsApi.stopProject(id, target)
      return res.data
    } catch (err) {
      console.error('停止项目失败:', err)
      throw err
    }
  }

  const syncStatus = async (id) => {
    try {
      const res = await projectsApi.syncProjectStatus(id)
      await fetchProjects()
      return res.data
    } catch (err) {
      console.error('同步状态失败:', err)
      throw err
    }
  }

  const getModuleLog = async (id, moduleIndex) => {
    try {
      const res = await projectsApi.getModuleLog(id, moduleIndex)
      return res.data
    } catch (err) {
      console.error('获取日志失败:', err)
      throw err
    }
  }

  const getModuleScript = async (id, moduleIndex) => {
    try {
      const res = await projectsApi.getModuleScript(id, moduleIndex)
      return res.data
    } catch (err) {
      console.error('获取脚本失败:', err)
      throw err
    }
  }

  const scanModules = async (id) => {
    try {
      const res = await projectsApi.scanModules(id)
      return res.data
    } catch (err) {
      console.error('扫描模块失败:', err)
      throw err
    }
  }

  return {
    projects,
    scaffolds,
    loading,
    currentProject,
    fetchProjects,
    fetchScaffolds,
    fetchProject,
    createProject,
    createGenericProject,
    importProject,
    updateProjectGit,
    addModule,
    updateModule,
    removeModule,
    pullProject,
    renameProject,
    deleteProject,
    startProject,
    stopProject,
    syncStatus,
    getModuleLog,
    getModuleScript,
    scanModules
  }
})
