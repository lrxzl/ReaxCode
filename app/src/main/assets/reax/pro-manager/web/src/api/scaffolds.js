import request from './request'

// 获取手脚架列表
export const getScaffolds = () => request.get('/scaffolds')
