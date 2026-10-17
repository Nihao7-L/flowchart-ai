import { defineConfig } from 'vitest/config';

/**
 * 前端单元测试配置（独立于 vite.config.ts，避免把构建/插件配置带进测试）。
 *
 * sceneAdapter 顶层 import 了 `@excalidraw/excalidraw`（体积巨大的真实渲染包），
 * 测试文件里统一用 `vi.mock('@excalidraw/excalidraw', ...)` 挡住它，
 * 因此这里用 node 环境即可，不引入 jsdom。
 */
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
