import React, { useState, useCallback, lazy, Suspense, useRef } from 'react';
import Sidebar from './components/Sidebar';
import ChatPanel from './components/ChatPanel';
// Excalidraw 体积巨大（~500KB JS + 144KB CSS），改为动态 import，
// 让它与首屏拆成不同 chunk，先渲染侧栏/聊天，画布异步加载，消除首屏白屏与卡顿
const Canvas = lazy(() => import('./components/Canvas'));
import type { CanvasSyncPayload, ChatMessage, ChatEvent, SceneIR, SceneElement } from './types';
import './App.css';

function App() {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [elements, setElements] = useState<SceneElement[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  // 会话 ID 必须可更换：后端用 sessionId 关联"当前图模型"，
  // 点「新建对话」若不换 ID，下一句话就会被当成"改上一张图"（v2-33）
  const [sessionId, setSessionId] = useState(() => crypto.randomUUID());
  // 三栏宽度（v2-24b）：左导航栏 / 右聊天栏可拖拽；中栏 flex:1 自适应
  const [leftWidth, setLeftWidth] = useState(240);
  const [rightWidth, setRightWidth] = useState(340);

  // 画布渲染问题的去重集合：同一问题只提示一次，避免刷屏
  const reportedIssuesRef = useRef<Set<string>>(new Set());

  // 是否已收到"终态"事件（result / error）。
  // 流读完时若仍为 false，说明连接是被中途切断的（例如后端生成超时、
  // 容器把 SSE 通道掐断）。这时必须明确告诉用户——否则界面上只会看到
  // "正在生成..."消失、然后什么也没有，用户无法区分"在跑"和"已经废了"。
  const settledRef = useRef(false);

  // 处理画布上报的渲染问题（画布本身没有 UI 反馈位，必须在这里显式告诉用户）
  const handleRenderIssue = useCallback((text: string) => {
    if (reportedIssuesRef.current.has(text)) return;
    reportedIssuesRef.current.add(text);
    setMessages(prev => [...prev, {
      role: 'error',
      text,
      timestamp: Date.now(),
    }]);
  }, []);

  // 处理 SSE 事件
  const handleSSEEvent = useCallback((event: ChatEvent) => {
    // result / error 都是终态：收到任一即代表本次生成已经给出了结果
    if (event.type === 'result' || event.type === 'error') {
      settledRef.current = true;
    }
    switch (event.type) {
      case 'thinking':
      case 'validation':
        setMessages(prev => [...prev, {
          role: 'agent',
          text: event.data,
          timestamp: Date.now(),
        }]);
        break;
      case 'result':
        try {
          const scene: SceneIR = JSON.parse(event.data);
          setElements(scene.elements);
          setMessages(prev => [...prev, {
            role: 'agent',
            text: `已生成 ${scene.elements.length} 个元素`,
            timestamp: Date.now(),
          }]);
        } catch {
          setMessages(prev => [...prev, {
            role: 'error',
            text: '解析结果失败',
            timestamp: Date.now(),
          }]);
        }
        break;
      case 'error':
        setMessages(prev => [...prev, {
          role: 'error',
          text: event.data,
          timestamp: Date.now(),
        }]);
        break;
    }
  }, []);

  // 发送消息到后端
  const handleSend = useCallback(async (text: string) => {
    if (isLoading) return;

    // 新一轮生成：清空上一轮的去重记录，保证新问题能被再次提示
    reportedIssuesRef.current.clear();
    settledRef.current = false;

    // 添加用户消息
    setMessages(prev => [...prev, {
      role: 'user',
      text,
      timestamp: Date.now(),
    }]);
    setIsLoading(true);

    try {
      const response = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId, message: text }),
      });

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }

      // 读取 SSE 流
      const reader = response.body?.getReader();
      if (!reader) throw new Error('No reader');

      const decoder = new TextDecoder();
      let buffer = '';

      // 逐块读取：用显式标志控制循环（不用 while (true)，ESLint no-constant-condition 会报错）
      let streamDone = false;
      while (!streamDone) {
        const { done, value } = await reader.read();
        streamDone = done;
        if (!value) continue;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          if (line.startsWith('data:')) {
            try {
              const event: ChatEvent = JSON.parse(line.slice(5).trim());
              handleSSEEvent(event);
            } catch {
              // 忽略解析失败的行
            }
          }
        }

        // 收到终态事件（result / error）后立刻停止等待流关闭。
        // 不能依赖服务端把响应体正常收尾：错误路径上响应可能悬着不关
        // （容器 ERROR dispatch 失败），前端若傻等 done，就会一直卡在
        // loading、输入框被禁用、连新消息都发不出去（2026-09-16 实测）。
        if (settledRef.current) {
          try {
            await reader.cancel();
          } catch {
            // 取消失败也无所谓：终态结果已经拿到手了
          }
          streamDone = true;
        }
      }

      // 流读完了却没等到 result / error —— 连接是被中途切断的。
      // 后端此时可能仍在跑（甚至稍后成功），但前端已不可能收到，
      // 必须如实告知，否则用户只会看到"正在生成"消失后一片沉默。
      if (!settledRef.current) {
        setMessages(prev => [...prev, {
          role: 'error',
          text: '生成中断：连接在返回结果前就断开了。常见原因是图形过于复杂、生成耗时超过服务端上限。请重试，或把需求拆成更小的图。',
          timestamp: Date.now(),
        }]);
      }
    } catch (error) {
      setMessages(prev => [...prev, {
        role: 'error',
        text: `请求失败: ${error instanceof Error ? error.message : String(error)}`,
        timestamp: Date.now(),
      }]);
    } finally {
      setIsLoading(false);
    }
  }, [isLoading, sessionId, handleSSEEvent]);

  // 新建对话
  const handleNewChat = useCallback(() => {
    setMessages([]);
    setElements([]);
    reportedIssuesRef.current.clear();
    // 换一个新会话 ID：老会话的模型留在后端，不会被下一次生成当成上下文
    setSessionId(crypto.randomUUID());
  }, []);

  // 画布现状同步：坐标 + 被删元素 + 用户手绘（v2-34）。
  // 防抖与"仅 AI 元素"过滤都在 Canvas 内完成。
  //
  // 为什么不能只送坐标：用户在前端的**删除**与**手绘**此前完全没有
  // 回写通道，后端模型因此与用户眼前所见持续分叉 —— 被删的线下一轮
  // 复活，手绘的线 AI 看不见又画一条，同一处出现两条线。
  const handleSync = useCallback(
    (payload: CanvasSyncPayload) => {
      fetch(`/api/model/canvas?sessionId=${sessionId}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      }).catch(console.error);
    },
    [sessionId],
  );

  // ===== 三栏拖拽分割线（v2-24b）=====
  // 用 pointer 事件 + setPointerCapture：拖出窗口也不丢 move/up，比 mouse 事件稳
  const DRAG_LIMITS = {
    left: { min: 200, max: 420 },
    right: { min: 280, max: 600 },
  } as const;
  const dragRef = useRef<{ side: 'left' | 'right'; startX: number; startWidth: number } | null>(null);

  const onSplitterDown = (side: 'left' | 'right') => (e: React.PointerEvent) => {
    e.preventDefault();
    dragRef.current = {
      side,
      startX: e.clientX,
      startWidth: side === 'left' ? leftWidth : rightWidth,
    };
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    document.body.style.userSelect = 'none';
  };

  const onSplitterMove = (e: React.PointerEvent) => {
    const s = dragRef.current;
    if (!s) return;
    const delta = e.clientX - s.startX;
    // 左分割线：向右拖 → 左栏变宽；右分割线：向右拖 → 右栏变窄
    const raw = s.side === 'left' ? s.startWidth + delta : s.startWidth - delta;
    const clamped = Math.min(DRAG_LIMITS[s.side].max, Math.max(DRAG_LIMITS[s.side].min, raw));
    if (s.side === 'left') setLeftWidth(clamped);
    else setRightWidth(clamped);
  };

  const onSplitterUp = (e: React.PointerEvent) => {
    if (!dragRef.current) return;
    dragRef.current = null;
    (e.currentTarget as HTMLElement).releasePointerCapture(e.pointerId);
    document.body.style.userSelect = '';
  };

  return (
    <div className="app">
      <Sidebar
        onNewChat={handleNewChat}
        style={{ width: leftWidth, minWidth: leftWidth }}
      />
      {/* 左分割线：拖拽控制左导航栏宽度 */}
      <div
        className="splitter"
        role="separator"
        aria-orientation="vertical"
        onPointerDown={onSplitterDown('left')}
        onPointerMove={onSplitterMove}
        onPointerUp={onSplitterUp}
      />
      {/* Canvas 是 lazy 的，必须有 Suspense 边界兜住加载期，否则首屏渲染会抛错 */}
      <Suspense fallback={<div className="canvas-loading">画布加载中…</div>}>
        <Canvas
          elements={elements}
          onSync={handleSync}
          onRenderIssue={handleRenderIssue}
        />
      </Suspense>
      {/* 右分割线：拖拽控制右聊天栏宽度 */}
      <div
        className="splitter"
        role="separator"
        aria-orientation="vertical"
        onPointerDown={onSplitterDown('right')}
        onPointerMove={onSplitterMove}
        onPointerUp={onSplitterUp}
      />
      <ChatPanel
        messages={messages}
        onSend={handleSend}
        isLoading={isLoading}
        style={{ width: rightWidth, minWidth: rightWidth }}
      />
    </div>
  );
}

export default App;
