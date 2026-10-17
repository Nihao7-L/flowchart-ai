import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// ChartFlow 前端：v2-24a Excalidraw 双通道白板
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        // 后端端口与 src/main/resources/application.yml 的 server.port 保持默认一致；
        // 若本地跳过 application.yml 改了端口，开发联调时需要同步这里
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  // 预构建巨型依赖，避免首屏按需 optimize 时整页重刷导致的“第一次特别慢”
  optimizeDeps: {
    include: ['@excalidraw/excalidraw', 'react', 'react-dom'],
  },
  build: {
    // 把 Excalidraw / React 拆成独立 vendor chunk，利于浏览器长效缓存、缩小首屏主包
    rollupOptions: {
      output: {
        manualChunks: {
          excalidraw: ['@excalidraw/excalidraw'],
          'react-vendor': ['react', 'react-dom'],
        },
      },
    },
  },
})
