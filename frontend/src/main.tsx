// 必须在 import App 之前引入，保证 Excalidraw 基础样式先生效、本方 App.css 可覆盖
import '@excalidraw/excalidraw/index.css'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import './index.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
