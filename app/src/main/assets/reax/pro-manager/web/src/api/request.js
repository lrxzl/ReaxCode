import axios from 'axios'

const request = axios.create({
  baseURL: '/api',
  timeout: 90000
})

// 响应拦截器
request.interceptors.response.use(
  response => response.data,
  error => {
    const message = error.response?.data?.error || error.message || '请求失败'
    return Promise.reject(new Error(message))
  }
)

export default request
