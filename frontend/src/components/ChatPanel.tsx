import React, { useRef, useEffect, useState } from 'react';
import type { ChatMessage } from '../types';

interface ChatPanelProps {
  messages: ChatMessage[];
  onSend: (text: string) => void;
  isLoading: boolean;
  style?: React.CSSProperties;
}

/**
 * 右侧聊天栏（340px）
 * - 聊天消息列表（user/agent/tool/error 气泡）
 * - 聊天输入框 + 发送按钮
 */
const ChatPanel: React.FC<ChatPanelProps> = ({ messages, onSend, isLoading, style }) => {
  const logRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  /** 本次生成的已等待秒数（纯前端计时，不依赖服务端心跳） */
  const [elapsed, setElapsed] = useState(0);

  // 自动滚动到底部
  useEffect(() => {
    if (logRef.current) {
      logRef.current.scrollTop = logRef.current.scrollHeight;
    }
  }, [messages, elapsed]);

  // 等待计时：复杂架构图实测要跑 2~5 分钟，若只显示一个静止的"正在生成..."，
  // 用户无法区分"还在跑"和"已经卡死"，只能干等或刷新页面。
  useEffect(() => {
    if (!isLoading) {
      setElapsed(0);
      return;
    }
    const startedAt = Date.now();
    const timer = window.setInterval(() => {
      setElapsed(Math.floor((Date.now() - startedAt) / 1000));
    }, 1000);
    return () => window.clearInterval(timer);
  }, [isLoading]);

  const handleSend = () => {
    const input = inputRef.current;
    if (!input) return;
    const text = input.value.trim();
    if (!text) return;
    onSend(text);
    input.value = '';
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  return (
    <div className="chat-panel" style={style}>
      <div className="chat-log" ref={logRef}>
        {messages.map((msg, i) => (
          <div key={i} className={`msg ${msg.role}`}>
            {msg.text}
          </div>
        ))}
        {isLoading && (
          <div className="msg agent">
            正在生成...
            {elapsed > 0 && ` 已等待 ${elapsed} 秒`}
            {elapsed >= 90 && '（复杂架构图本轮实测需 100~260 秒，请再等一下）'}
          </div>
        )}
      </div>

      <div className="chat-input-bar">
        <input
          ref={inputRef}
          className="chat-input"
          placeholder="输入消息..."
          onKeyDown={handleKeyDown}
          disabled={isLoading}
        />
        <button
          className="chat-send-btn"
          onClick={handleSend}
          disabled={isLoading}
        >
          发送
        </button>
      </div>
    </div>
  );
};

export default ChatPanel;
