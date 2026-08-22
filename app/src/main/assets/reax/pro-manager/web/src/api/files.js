import request from './request'

// 列出目录
export const listDir = (dirPath) => request.get('/files/list', { params: { path: dirPath } })

// 读取文件
export const readFile = (filePath) => request.get('/files/read', { params: { path: filePath } })

// 写入文件
export const writeFile = (filePath, content) => request.post('/files/write', { path: filePath, content })

// 创建目录
export const createDir = (dirPath) => request.post('/files/mkdir', { path: dirPath })

// 删除
export const deleteFile = (targetPath) => request.post('/files/delete', { path: targetPath })

// 重命名
export const renameFile = (oldPath, newName) => request.post('/files/rename', { path: oldPath, newName })

// 复制
export const copyFile = (src, dest) => request.post('/files/copy', { src, dest })

// 移动
export const moveFile = (src, dest) => request.post('/files/move', { src, dest })

// 搜索
export const searchFiles = (dirPath, keyword) => request.get('/files/search', { params: { path: dirPath, keyword } })

// 获取可用根目录
export const getRoots = () => request.get('/files/roots')

// 获取用户主目录
export const getHomeDir = () => request.get('/files/home')

// 获取目录大小
export const getDirSize = (dirPath) => request.get('/files/size', { params: { path: dirPath } })

// 下载文件
export const getDownloadUrl = (filePath) => `/api/files/download?path=${encodeURIComponent(filePath)}`

// 获取局域网IP
export const getLanIp = () => request.get('/files/ip')

// 上传文件
export const uploadFiles = (dirPath, fileList) => {
  const formData = new FormData()
  formData.append('dir', dirPath)
  for (const file of fileList) {
    formData.append('files', file)
  }
  return request.post('/files/upload', formData)
}
