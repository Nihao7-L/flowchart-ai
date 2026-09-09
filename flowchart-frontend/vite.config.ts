import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import http from 'http'

// 进程内清掉系统全局 HTTP_PROXY，避免 vite 的 http-proxy 中间件在转发
// /api/* 时走代理服务器（代理不识本地后端 → ECONNREFUSED）。
// 只影响 vite dev server 进程，不污染用户 shell 环境。
delete process.env.http_proxy
delete process.env.HTTP_PROXY
delete process.env.https_proxy
delete process.env.HTTPS_PROXY

// 显式构造一个 http.Agent 传给 proxy：传 truthy agent 后 http-proxy
// library 会跳过内部 getAgent()（那个函数会读 env HTTP_PROXY），
// 强制走直连，彻底绕开系统代理。
const directAgent = new http.Agent({ keepAlive: true, maxSockets: 32 })

// /api/* 代理到 Spring Boot 后端（8080），开发期无需跨域配置
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
        agent: directAgent
      }
    }
  }
})
