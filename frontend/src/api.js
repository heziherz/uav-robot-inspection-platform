import axios from 'axios'

/**
 * 全局 axios 拦截器（只需在 main.jsx 里 import 一次，所有页面自动生效）。
 *
 * ① 请求拦截器：自动为每个请求附加 JWT
 * ② 响应拦截器：401（未登录/令牌过期）→ 清除本地登录态并回到登录页
 */

// ① 请求：附带 token
axios.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// ② 响应：统一处理 401
axios.interceptors.response.use(
  res => res,
  err => {
    if (err.response?.status === 401) {
      localStorage.removeItem('token')
      localStorage.removeItem('user')
      window.location.reload()          // 回到登录页
    }
    return Promise.reject(err)
  }
)

export default axios
