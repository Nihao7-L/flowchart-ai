import React from 'react';

interface SidebarProps {
  onNewChat: () => void;
  style?: React.CSSProperties;
}

/**
 * 左导航栏（240px）
 * - 新建对话（顶部蓝色按钮）
 * - 历史对话列表（中下部）
 *
 * 注：v2-24a 收尾按用户 2026-09-16 决定，已移除「素材库」按钮与「主题切换」按钮。
 *     Excalidraw 自带的素材库面板仍可从画布右上角按钮打开；全站固定浅色（白底）。
 */
const Sidebar: React.FC<SidebarProps> = ({ onNewChat, style }) => {
  return (
    <div className="sidebar" style={style}>
      <div className="sidebar-header">
        <h1 className="sidebar-logo">ChartFlow</h1>
      </div>

      <button className="btn-new-chat" onClick={onNewChat}>
        + 新建对话
      </button>

      <div className="sidebar-section">
        <h3 className="sidebar-section-title">历史对话</h3>
        <div className="history-list">
          <div className="history-item">登录流程图</div>
          <div className="history-item">电商架构图</div>
          <div className="history-item">用户旅程图</div>
        </div>
      </div>
    </div>
  );
};

export default Sidebar;
