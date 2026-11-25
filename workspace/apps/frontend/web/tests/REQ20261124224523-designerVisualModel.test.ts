/**
 * REQ-20261124-224523 BPM-2 UI 迭代——设计器模型/令牌/i18n 契约用例（AC-3/AC-4/AC-5 模型面 + AC-6 样式面 + AC-12 i18n 面 + 确认③兼容回归）。
 *
 * 契约口径（contracts/REQ-20261124-224523.md AC + 委派单钉死事实）：
 *   - AC-3（模型面）：尺寸单源 DEFAULT_NODE_SIZES——全部 8 类白名单元素有尺寸条目；
 *     事件（图形 40/36）/网关（图形 50）含标签区=高度>图形，宽度不小于图形；
 *     EmbeddedSubProcess 保持 ≥200×120 容量（AC-4 前提）。Transform 自由 resize 收口
 *     在 designerVisual.test.tsx 以「Transform 插件从未实例化」断言（X6 装配面）。
 *   - AC-4：autoLayout 列距≥240、行距≥150（列距/行距=相邻列/行中心距，契约背景
 *     colGap=190<子流程宽 200 重叠的口径）；混排模型（含 EmbeddedSubProcess）两两矩形
 *     用 DEFAULT_NODE_SIZES 互斥不重叠；布局数据缺失时全节点有坐标且不产生 {0,0} 叠点。
 *   - 兼容性（变更清单钉死）：parseLayoutJson/serializeLayout/buildSavePayload 兼容不变；
 *     canConnect 白名单回归不破（确认③：连线白名单校验维持既有契约 AC-4 不变）。
 *   - AC-5（模型面）：resolveCanvasPalette grid alpha 上调——契约背景实测基线
 *     light 0.08 / dark 0.07 暗色不可辨，断言两主题 grid alpha 严格大于基线；
 *     网格肉眼可辨/发丝级观感=浏览器走查项。
 *   - AC-6（样式面）：global.css 存在 minimap 主题化覆盖块且色值走 CSS 变量层（禁硬编码 hex）；
 *     暗色无白底块的实际观感=浏览器走查项。
 *   - AC-12（i18n 面）：新增键 zh-CN/en-US 同步存在——designer.palette.group.*（变更清单）
 *     与 designer.toolbar.refresh（委派单钉死事实 6）；zh/en designer 子树叶子键集一致。
 *
 * 装配：纯 TS 模型面（node 环境零 DOM 依赖）+ global.css 文本断言（global-css.test.ts 先例）。
 */
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import {
  autoLayout,
  buildSavePayload,
  canConnect,
  connect,
  DEFAULT_NODE_SIZES,
  emptyModel,
  parseLayoutJson,
  PALETTE_ITEMS,
  serializeLayout,
} from '../src/pages/workflow/designerModel';
import {
  ACME_EXT_NS,
  BPMN_MODEL_NS,
  resolveCanvasPalette,
  type BpmnElementType,
  type BpmnModel,
  type BpmnNodeModel,
} from '@acme/core';
import { workflow as zhWorkflow } from '../../packages/core/src/i18n/resources/zh-CN/workflow';
import { workflow as enWorkflow } from '../../packages/core/src/i18n/resources/en-US/workflow';

const node = (overrides: Partial<BpmnNodeModel> & { id: string; type: BpmnNodeModel['type'] }): BpmnNodeModel => ({
  name: '',
  executionListeners: [],
  ...overrides,
});

const sizeOf = (type: BpmnElementType): { width: number; height: number } => DEFAULT_NODE_SIZES[type];

/** 混排模型（AC-4 口径：含 EmbeddedSubProcess + 边界事件 + 网关分支）。 */
const mixedModel = (): BpmnModel => ({
  nodes: [
    node({ id: 'start', type: 'StartEvent' }),
    node({ id: 'xor', type: 'XOR' }),
    node({ id: 'task-1', type: 'UserTask' }),
    node({ id: 'sub-1', type: 'EmbeddedSubProcess' }),
    node({ id: 'end', type: 'EndEvent' }),
    node({ id: 'boundary-1', type: 'BoundaryEvent', attachedToRef: 'task-1' }),
  ],
  edges: [
    { id: 'f0', sourceId: 'start', targetId: 'xor' },
    { id: 'f1', sourceId: 'xor', targetId: 'task-1' },
    { id: 'f2', sourceId: 'xor', targetId: 'sub-1' },
    { id: 'f3', sourceId: 'task-1', targetId: 'end' },
    { id: 'f4', sourceId: 'sub-1', targetId: 'end' },
  ],
});

interface Rect { id: string; x: number; y: number; width: number; height: number }

const overlaps = (a: Rect, b: Rect): boolean =>
  a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height;

/** 从色值字符串提取 alpha（rgba() 或 #RRGGBBAA；不透明 #RRGGBB 视为 1）。 */
const alphaOf = (color: string): number => {
  const rgba = /rgba?\(\s*[\d.]+\s*,\s*[\d.]+\s*,\s*[\d.]+\s*,\s*([\d.]+)\s*\)/.exec(color);
  if (rgba) return Number(rgba[1]);
  const hex = color.trim();
  if (/^#[0-9a-fA-F]{8}$/.test(hex)) return parseInt(hex.slice(7, 9), 16) / 255;
  if (/^#[0-9a-fA-F]{6}$/.test(hex)) return 1;
  throw new Error(`无法识别的网格色格式（用例需按实际令牌格式适配）：${color}`);
};

const leafPaths = (value: unknown, prefix: string): string[] => {
  if (value === null || typeof value !== 'object') return [prefix];
  return Object.entries(value as Record<string, unknown>).flatMap(([k, v]) => leafPaths(v, `${prefix}.${k}`));
};

// ————— AC-3（模型面）：尺寸单源 DEFAULT_NODE_SIZES —————

describe('REQ20261124224523 节点尺寸单源（AC-3 模型面）', () => {
  it('should_cover_every_palette_type_when_default_sizes_queried', () => {
    // 尺寸单源完整性：8 类白名单元素每类都有正的像素尺寸条目
    expect(PALETTE_ITEMS).toHaveLength(8);
    for (const type of PALETTE_ITEMS) {
      const s = sizeOf(type);
      expect(Number.isFinite(s.width) && s.width > 0, `${type}.width 应为正像素值`).toBe(true);
      expect(Number.isFinite(s.height) && s.height > 0, `${type}.height 应为正像素值`).toBe(true);
    }
  });

  it('should_reserve_label_area_for_events_and_gateways_when_sizes_defined', () => {
    // 语义形状适配（变更清单钉死）：事件图形 40（Start/End）/36（Boundary）、网关 50；
    // 标签区=高度>图形；宽度不小于图形（标签/图形不裁剪的下限）
    const cases = [
      ['StartEvent', 40], ['EndEvent', 40], ['BoundaryEvent', 36], ['XOR', 50], ['AND', 50],
    ] as const;
    for (const [type, graphic] of cases) {
      const s = sizeOf(type);
      expect(s.width, `${type}.width 应不小于图形 ${graphic}`).toBeGreaterThanOrEqual(graphic);
      expect(s.height, `${type}.height 应大于图形 ${graphic}（含标签区）`).toBeGreaterThan(graphic);
    }
  });

  it('should_keep_subprocess_capacity_when_sizes_defined', () => {
    // EmbeddedSubProcess 保持 200×120 容量（AC-4 列距/行距前提；契约背景钉死）
    const s = sizeOf('EmbeddedSubProcess');
    expect(s.width, '子流程宽 ≥200').toBeGreaterThanOrEqual(200);
    expect(s.height, '子流程高 ≥120').toBeGreaterThanOrEqual(120);
  });
});

// ————— AC-4：autoLayout 列距/行距/不重叠/{0,0} 兜底 —————

describe('REQ20261124224523 自动布局间距与兜底（AC-4）', () => {
  it('should_position_every_node_when_layout_missing', () => {
    const model = mixedModel();
    const positions = autoLayout(model);
    expect(positions.size, '缺省布局时全节点应有坐标').toBe(model.nodes.length);
    for (const [id, pos] of positions) {
      expect(Number.isFinite(pos.x), `${id}.x`).toBe(true);
      expect(Number.isFinite(pos.y), `${id}.y`).toBe(true);
    }
  });

  it('should_avoid_zero_zero_pileup_when_positions_missing', () => {
    // 钉死事实 4：位置缺省兜底不再把无布局节点全部叠于 {0,0}
    const model = mixedModel();
    const positions = autoLayout(model);
    const seen = new Set<string>();
    for (const [id, pos] of positions) {
      const key = `${pos.x},${pos.y}`;
      expect(seen.has(key), `节点 ${id} 不应与已放置节点完全同点（${key}）`).toBe(false);
      seen.add(key);
    }
  });

  it('should_keep_column_gap_240_and_row_gap_150_when_mixed_model_laid_out', () => {
    // 钉死事实 4：列距≥240、行距≥150（相邻列/行坐标最小间隔）
    const positions = autoLayout(mixedModel());
    const xs = [...new Set([...positions.values()].map((p) => p.x))].sort((a, b) => a - b);
    const ys = [...new Set([...positions.values()].map((p) => p.y))].sort((a, b) => a - b);
    expect(xs.length, '混排模型应产生多列').toBeGreaterThan(1);
    for (let i = 1; i < xs.length; i += 1) {
      expect(xs[i] - xs[i - 1], `相邻列距（${xs[i - 1]}→${xs[i]}）≥240`).toBeGreaterThanOrEqual(240);
    }
    for (let i = 1; i < ys.length; i += 1) {
      expect(ys[i] - ys[i - 1], `相邻行距（${ys[i - 1]}→${ys[i]}）≥150`).toBeGreaterThanOrEqual(150);
    }
  });

  it('should_not_overlap_any_two_nodes_when_mixed_model_laid_out', () => {
    // 强 oracle：按 DEFAULT_NODE_SIZES（尺寸单源）展开矩形，两两互斥（含 EmbeddedSubProcess）
    const model = mixedModel();
    const positions = autoLayout(model);
    const rects: Rect[] = model.nodes.map((n) => ({
      id: n.id,
      x: positions.get(n.id)?.x ?? Number.NaN,
      y: positions.get(n.id)?.y ?? Number.NaN,
      width: sizeOf(n.type).width,
      height: sizeOf(n.type).height,
    }));
    for (let i = 0; i < rects.length; i += 1) {
      for (let j = i + 1; j < rects.length; j += 1) {
        const a = rects[i];
        const b = rects[j];
        expect(overlaps(a, b), `节点 ${a.id} 与 ${b.id} 矩形不应重叠`).toBe(false);
      }
    }
  });
});

// ————— 兼容性回归：布局 JSON / 保存载荷（变更清单钉死「兼容不变」） —————

describe('REQ20261124224523 布局与保存载荷兼容回归（兼容性钉死）', () => {
  it('should_reject_malformed_layout_json_when_parsing', () => {
    // 兼容回归（REQ-20261122-023441 AC-7 口径不变）：缺省/损坏/形状不符 → null 不入画布
    expect(parseLayoutJson(null)).toBeNull();
    expect(parseLayoutJson('')).toBeNull();
    expect(parseLayoutJson('not-json')).toBeNull();
    expect(parseLayoutJson('{"nodes":"bad"}')).toBeNull();
    expect(parseLayoutJson('{"nodes":[{"id":"a"}]}')).toBeNull();
    // 正例：合法形状解析回坐标（往返一致）
    expect(parseLayoutJson(serializeLayout([{ id: 'a', x: 1.5, y: -2 }])))
      .toEqual({ nodes: [{ id: 'a', x: 1.5, y: -2 }] });
  });

  it('should_keep_save_payload_shape_when_model_saved', () => {
    // 兼容回归（REQ-20261122-023441 AC-6 口径不变）：bpmnXml 含标准+acme 扩展 ns，layoutJson 单载荷可解析
    const model = emptyModel();
    model.nodes.push(node({ id: 'task-1', type: 'UserTask', assignee: '${initiator}' }));
    model.edges.push({ id: 'f0', sourceId: model.nodes[0]?.id ?? '', targetId: 'task-1' });
    const positions = new Map([
      [model.nodes[0]?.id ?? '', { x: 60, y: 80 }],
      ['task-1', { x: 360, y: 80 }],
      ['ghost', { x: 999, y: 999 }],
    ]);
    const payload = buildSavePayload(model, positions);
    expect(payload.bpmnXml).toContain(BPMN_MODEL_NS);
    expect(payload.bpmnXml).toContain(ACME_EXT_NS);
    const layout = parseLayoutJson(payload.layoutJson);
    expect(layout, 'layoutJson 应可解析').not.toBeNull();
    expect(layout?.nodes.map((n) => n.id).sort()).toEqual([model.nodes[0]?.id ?? '', 'task-1'].sort());
  });
});

// ————— 兼容性回归：连线白名单（确认③「AC-4 契约不变」） —————

describe('REQ20261124224523 连线白名单回归（确认③）', () => {
  const connectedModel = (): BpmnModel => ({
    nodes: [
      node({ id: 'start', type: 'StartEvent' }),
      node({ id: 'task-1', type: 'UserTask', assignee: '1001' }),
      node({ id: 'svc-1', type: 'ServiceTask', serviceRef: 's', callbackPath: '/cb' }),
      node({ id: 'xor', type: 'XOR' }),
      node({ id: 'and', type: 'AND' }),
      node({ id: 'sub-1', type: 'EmbeddedSubProcess' }),
      node({ id: 'boundary-1', type: 'BoundaryEvent', attachedToRef: 'task-1' }),
      node({ id: 'end', type: 'EndEvent' }),
    ],
    edges: [{ id: 'f0', sourceId: 'start', targetId: 'task-1' }],
  });

  it('should_keep_whitelist_connection_rules_when_canvas_rewired', () => {
    const model = connectedModel();
    // 正例矩阵（白名单方向放行不变；含边界事件流出）
    for (const [src, dst] of [
      ['start', 'end'], ['start', 'xor'], ['start', 'and'], ['task-1', 'end'],
      ['svc-1', 'end'], ['xor', 'sub-1'], ['boundary-1', 'end'],
    ] as const) {
      expect(canConnect(model, src, dst).ok, `${src}→${dst} 应放行`).toBe(true);
    }
    // 拒绝理由枚举回归（每规则一例；reason 字符串钉死不变）
    expect(canConnect(model, 'task-1', 'task-1')).toMatchObject({ ok: false, reason: 'selfLoop' });
    expect(canConnect(model, 'task-1', 'start')).toMatchObject({ ok: false, reason: 'startIncoming' });
    expect(canConnect(model, 'end', 'task-1')).toMatchObject({ ok: false, reason: 'endOutgoing' });
    expect(canConnect(model, 'task-1', 'boundary-1')).toMatchObject({ ok: false, reason: 'targetBoundary' });
    expect(canConnect(model, 'ghost', 'end')).toMatchObject({ ok: false, reason: 'unknownEndpoint' });
    expect(canConnect(model, 'start', 'task-1')).toMatchObject({ ok: false, reason: 'duplicate' });
    // connect 拒绝后模型不变 + 正例落边（既有公开行为）
    const { model: unchanged, edge: rejected } = connect(connectedModel(), 'task-1', 'task-1', 'f-x');
    expect(rejected).toBeNull();
    expect(unchanged.edges.every((e) => e.id !== 'f-x')).toBe(true);
    const { model: next, edge: added } = connect({ nodes: connectedModel().nodes, edges: [] }, 'start', 'end', 'f-new');
    expect(added?.sourceId).toBe('start');
    expect(next.edges.some((e) => e.id === 'f-new')).toBe(true);
  });
});

// ————— AC-5（模型面）：resolveCanvasPalette grid alpha 上调 —————

describe('REQ20261124224523 画布调色板网格令牌（AC-5 模型面）', () => {
  it('should_raise_grid_alpha_above_baseline_when_canvas_palette_resolved', () => {
    // 契约背景实测基线：light 0.08 / dark 0.07 不可辨 → 上调；两主题均严格大于基线
    const darkGrid = resolveCanvasPalette('dark').grid;
    const lightGrid = resolveCanvasPalette('light').grid;
    expect(alphaOf(darkGrid), `dark grid alpha（${darkGrid}）应 > 0.07`).toBeGreaterThan(0.07);
    expect(alphaOf(lightGrid), `light grid alpha（${lightGrid}）应 > 0.08`).toBeGreaterThan(0.08);
  });

  it('should_resolve_palette_tokens_for_both_themes_when_canvas_themed', () => {
    // 画布令牌面完整性：背景/网格/选中/连线/节点填充在两主题下均为非空令牌值
    for (const theme of ['dark', 'light'] as const) {
      const palette = resolveCanvasPalette(theme);
      for (const key of ['background', 'grid', 'selection', 'edge', 'nodeFill', 'errorMark'] as const) {
        expect(typeof palette[key] === 'string' && palette[key].length > 0, `${theme}.${key} 应为非空令牌`).toBe(true);
      }
    }
  });
});

// ————— AC-6（样式面）：minimap 主题化 CSS 覆盖 —————

describe('REQ20261124224523 minimap 主题化样式（AC-6 样式面）', () => {
  const css = readFileSync(new URL('../src/styles/global.css', import.meta.url), 'utf8');

  it('should_theme_minimap_with_css_variables_when_global_css_loaded', () => {
    // 变更清单钉死：minimap 主题化所需 CSS 覆盖（色值走变量层，禁硬编码）。
    // 三个分档断言：块存在；块内不硬编码 hex；合并体含 var(--) 令牌取色
    // （允许纯 reset 块如 box-shadow:none，不强制每块都含变量）。
    const blocks = [...css.matchAll(/([^{}]*minimap[^{}]*)\{([^{}]*)\}/gi)];
    expect(blocks.length, 'global.css 应存在 minimap 主题化覆盖块（暗色无白底块的样式承载）').toBeGreaterThan(0);
    for (const [, selector, body] of blocks) {
      expect(body, `${selector} 不得硬编码 hex 色值`).not.toMatch(/#[0-9a-fA-F]{3,8}\b/);
    }
    const combined = blocks.map(([, , body]) => body).join(' ');
    expect(combined, 'minimap 覆盖块应含 CSS 变量取色').toContain('var(--');
  });
});

// ————— AC-12（i18n 面）：新增键 zh-CN/en-US 同步存在 —————

describe('REQ20261124224523 设计器 i18n 键同步（AC-12）', () => {
  it('should_define_toolbar_refresh_key_in_both_locales_when_toolbar_rewired', () => {
    // 委派单钉死事实 6：刷新按钮 aria-label=刷新键文案（toolbar.refresh）
    expect(zhWorkflow.designer.toolbar.refresh.length, 'zh-CN toolbar.refresh 应存在').toBeGreaterThan(0);
    expect(enWorkflow.designer.toolbar.refresh.length, 'en-US toolbar.refresh 应存在').toBeGreaterThan(0);
  });

  it('should_define_palette_group_keys_in_both_locales_when_groups_added', () => {
    // 契约变更清单：Palette 分组小标题（事件/网关/活动）新增文案键（zh/en 同步）
    const zhPalette = zhWorkflow.designer.palette as unknown as Record<string, unknown>;
    const enPalette = enWorkflow.designer.palette as unknown as Record<string, unknown>;
    const zhGroup = zhPalette.group as Record<string, string> | undefined;
    const enGroup = enPalette.group as Record<string, string> | undefined;
    expect(zhGroup, 'zh-CN 应存在 designer.palette.group.* 键').toBeTruthy();
    expect(enGroup, 'en-US 应存在 designer.palette.group.* 键').toBeTruthy();
    const zhKeys = Object.keys(zhGroup ?? {}).sort();
    const enKeys = Object.keys(enGroup ?? {}).sort();
    expect(zhKeys.length, '分组键非空').toBeGreaterThan(0);
    for (const key of zhKeys) {
      expect(String(zhGroup?.[key] ?? '').length, `zh group.${key} 非空文案`).toBeGreaterThan(0);
      expect(String(enGroup?.[key] ?? '').length, `en group.${key} 非空文案`).toBeGreaterThan(0);
    }
    expect(enKeys, 'zh/en 分组键集一致').toEqual(zhKeys);
  });

  it('should_keep_designer_i18n_key_sets_in_parity_when_keys_added', () => {
    // zh/en designer 子树叶子键集完全一致（「zh/en 同步」的强口径，防单侧漏键）
    const zhPaths = leafPaths(zhWorkflow.designer as unknown as Record<string, unknown>, 'designer').sort();
    const enPaths = leafPaths(enWorkflow.designer as unknown as Record<string, unknown>, 'designer').sort();
    expect(enPaths, 'en-US designer 叶子键集应与 zh-CN 一致').toEqual(zhPaths);
  });
});
