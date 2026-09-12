import { useState } from 'react'
import './App.css'

function App() {
  const [count, setCount] = useState(0)
  return (
    <div className="app">
      <h1>ChartFlow Frontend</h1>
      <p>AI 图表生成器前端骨架（空壳）。后续在此生长 Excalidraw 双通道白板。</p>
      <button onClick={() => setCount((c) => c + 1)}>count is {count}</button>
    </div>
  )
}

export default App
