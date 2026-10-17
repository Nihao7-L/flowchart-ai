import { describe, it, expect, vi } from 'vitest';

/**
 * 挡住真实 Excalidraw 渲染包（sceneAdapter 顶层 import 会加载它，体积巨大且依赖 DOM）。
 * 用「恒等转换器」代替：骨架原样透传，便于直接断言契约映射与 normalize / 三级降级。
 */
vi.mock('@excalidraw/excalidraw', () => ({
  convertToExcalidrawElements: (skeletons: unknown[]): unknown[] => skeletons,
}));

import {
  convertScene,
  isFullyBoundArrow,
  isModelElement,
  isUserElement,
  MODEL_FLAG,
  planRemoval,
  toSkeleton,
  collectUnboundArrows,
} from './sceneAdapter';
import type { SceneElement } from '../types';

describe('MODEL_FLAG / isModelElement', () => {
  it('MODEL_FLAG 常量为 chartflowModel', () => {
    expect(MODEL_FLAG).toBe('chartflowModel');
  });

  it('customData.chartflowModel === true 才是 AI 生成元素', () => {
    expect(isModelElement({ customData: { chartflowModel: true } })).toBe(true);
    expect(isModelElement({ customData: { chartflowModel: false } })).toBe(false);
    expect(isModelElement({ customData: {} })).toBe(false);
    expect(isModelElement({})).toBe(false);
    expect(isModelElement({ customData: null })).toBe(false);
  });
});

describe('isFullyBoundArrow', () => {
  it('两端都绑定的 arrow 才算 fully bound', () => {
    expect(isFullyBoundArrow({ type: 'arrow', startBinding: { elementId: 'a' }, endBinding: { elementId: 'b' } })).toBe(true);
  });
  it('只绑一端（自由端）不算', () => {
    expect(isFullyBoundArrow({ type: 'arrow', startBinding: { elementId: 'a' } })).toBe(false);
    expect(isFullyBoundArrow({ type: 'arrow', endBinding: { elementId: 'a' } })).toBe(false);
    expect(isFullyBoundArrow({ type: 'arrow' })).toBe(false);
  });
  it('非 arrow 即使两端都有绑定也不算', () => {
    expect(isFullyBoundArrow({ type: 'line', startBinding: {}, endBinding: {} })).toBe(false);
  });
});

describe('isUserElement', () => {
  it('带模型标记的不是用户元素，有 containerId 的附属文字也不算', () => {
    expect(isUserElement({ type: 'rectangle', customData: { chartflowModel: true } })).toBe(false);
    expect(isUserElement({ type: 'text', containerId: 'x' })).toBe(false);
  });
  it('无标记无 containerId 的是用户元素', () => {
    expect(isUserElement({ type: 'rectangle' })).toBe(true);
  });
});

describe('toSkeleton（字段白名单）', () => {
  it('只搬运白名单内且有值的字段，剔除语义层 group', () => {
    const sk = toSkeleton({
      id: 'r1',
      type: 'rectangle',
      x: 0,
      y: 0,
      width: 100,
      height: 50,
      label: { text: 'hi' },
      group: '模块A', // 语义层，刻意不进白名单
    } as unknown as SceneElement);
    expect(sk.type).toBe('rectangle');
    expect(sk.id).toBe('r1');
    expect(sk.label).toEqual({ text: 'hi' });
    expect(sk).not.toHaveProperty('group');
  });
});

describe('planRemoval（删除 + 连坐）', () => {
  it('模型里不在画布上的元素要删，且连坐挂在它上面的模型箭头', () => {
    const model: SceneElement[] = [
      { id: 'n1', type: 'rectangle' },
      { id: 'a12', type: 'arrow', start: { id: 'n0' }, end: { id: 'n1' } },
    ];
    // 画布上只剩 n1 不存在、只有别的元素；a12 连的是 n1 → 也要连坐删掉
    const { removedIds } = planRemoval(model, [{ id: 'other' }], []);
    expect(removedIds).toContain('n1');
    expect(removedIds).toContain('a12');
  });

  it('本次转换被丢弃的元素不算用户删除（不得误报）', () => {
    const model: SceneElement[] = [{ id: 'n1', type: 'rectangle' }];
    const { removedIds } = planRemoval(model, [], ['n1']);
    expect(removedIds).not.toContain('n1');
  });

  it('画布上还活着的模型元素不删', () => {
    const model: SceneElement[] = [{ id: 'n1', type: 'rectangle' }];
    const { removedIds } = planRemoval(model, [{ id: 'n1' }], []);
    expect(removedIds).not.toContain('n1');
  });
});

describe('collectUnboundArrows（模型绑着、画布已解绑的箭头）', () => {
  it('模型绑定、画布无绑定且有有限几何 → 上报', () => {
    const model: SceneElement[] = [{ id: 'a1', type: 'arrow', start: { id: 'n1' }, end: { id: 'n2' } }];
    const briefs = collectUnboundArrows(model, [
      { id: 'a1', type: 'arrow', x: 10, y: 20, points: [[0, 0], [30, 40]] },
    ]);
    expect(briefs).toEqual([{ id: 'a1', x: 10, y: 20, points: [[0, 0], [30, 40]] }]);
  });

  it('画布上仍有绑定的箭头不上报', () => {
    const model: SceneElement[] = [{ id: 'a1', type: 'arrow', start: { id: 'n1' }, end: { id: 'n2' } }];
    const briefs = collectUnboundArrows(model, [
      { id: 'a1', type: 'arrow', startBinding: { elementId: 'n1' }, endBinding: { elementId: 'n2' }, x: 0, y: 0, points: [[0, 0], [1, 1]] },
    ]);
    expect(briefs).toEqual([]);
  });

  it('模型没写绑定/坐标非有限的箭头不上报', () => {
    const model: SceneElement[] = [{ id: 'a1', type: 'line' }];
    const briefs = collectUnboundArrows(model, [
      { id: 'a1', type: 'arrow', x: NaN, points: [[0, 0], [1, 1]] },
    ]);
    expect(briefs).toEqual([]);
  });
});

describe('convertScene', () => {
  it('空输入 → 空元素且无 dropped（A3：空场景不当成成功）', () => {
    expect(convertScene([])).toEqual({ elements: [], dropped: [] });
  });

  it('契约外未知类型被隔离丢弃，不连累整批（A1b）', () => {
    const out = convertScene([{ id: 'l1', type: 'lifeline' } as unknown as SceneElement]);
    expect(out.elements).toEqual([]);
    expect(out.dropped).toContain('l1');
  });

  it('text 缺正文被丢（最后一道兜底）', () => {
    const out = convertScene([{ id: 't1', type: 'text', text: '  ' }]);
    expect(out.dropped).toContain('t1');
    expect(out.elements).toEqual([]);
  });

  it('正常 rectangle → 转出 1 个元素并打上模型标记', () => {
    const out = convertScene([
      { id: 'r1', type: 'rectangle', x: 0, y: 0, width: 100, height: 50 },
    ]);
    expect(out.elements.length).toBe(1);
    expect(isModelElement(out.elements[0])).toBe(true);
    expect(out.elements[0].type).toBe('rectangle');
  });

  it('magicframe 降级为 frame（0.18 迁移）', () => {
    const out = convertScene([
      { id: 'f1', type: 'magicframe', x: 0, y: 0, width: 200, height: 150, name: '框' },
    ] as unknown as SceneElement[]);
    expect(out.elements.length).toBe(1);
    expect(out.elements[0].type).toBe('frame');
  });
});
