import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    name: 'ProjectList',
    component: () => import('../views/ProjectList.vue')
  },
  {
    path: '/project/:id',
    name: 'ProjectDetail',
    component: () => import('../views/ProjectDetail.vue'),
    props: true
  },
  {
    path: '/files',
    name: 'StandaloneFileManager',
    component: () => import('../views/FileManager.vue'),
    props: true
  },
  {
    path: '/project/:id/files',
    name: 'FileManager',
    component: () => import('../views/FileManager.vue'),
    props: true
  },
  {
    path: '/project/:id/git',
    name: 'GitHistory',
    component: () => import('../views/GitHistory.vue'),
    props: true
  },
  {
    path: '/project/:id/terminal',
    name: 'TerminalView',
    component: () => import('../views/TerminalView.vue'),
    props: true
  },
  {
    path: '/terminal',
    name: 'GlobalTerminal',
    component: () => import('../views/TerminalView.vue')
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router

