// @vitest-environment jsdom
/**
 * REQ-20261124-224523 BPM-2 UI 迭代——设计器视觉/画布装配契约用例（AC-1/AC-2 组件面 + AC-7 面板面 + AC-8/AC-9 页面装配面 + AC-3/AC-13/AC-14 X6 装配接线面 + AC-11 只读回归面）。
 *
 * 契约口径（contracts/REQ-20261124-224523.md AC + 确认②③④ + 委派单钉死事实）：
 *   - AC-1：BpmnNodeView 根 div 宽高=node.size() 像素值；node 为 null 回退默认像素值；
 *     根 div 不出现 height:'100%'（Chromium foreignObject bug 根因）。
 *   - AC-2：事件=圆形、网关=菱形、任务/子流程=圆角矩形；事件/网关名称标签位于图形下方完整可读；
 *     色值全走令牌（断言 CSS 变量/令牌引用存在，不硬编码 hex）；Palette 分组标题三组。
 *   - AC-7：ValidationPanel idle/pass 一行紧凑状态条（无 antd Empty 占位）；
 *     fail 逐条 nodeId+message 且点击触发 onLocate(nodeId)；fail/backend 列表内滚有上限。
 *   - AC-8：DesignerPage 根高度口径 100vh−184px（高度分解=Header 64+TabBar 36+Content padding 84）。
 *   - AC-9：工具栏 流程名/状态 Tag/脏标记 与操作按钮组分区；刷新按钮 aria-label=toolbar.refresh
 *     键文案+ReloadOutlined+可点击不报错；保存/部署=deploy(79) Auth 分控（既有行为回归）。
 *   - AC-13（接线面）：connecting.connectionPoint='boundary'（端点止于节点边界）；
 *     Delete 键绑定（仅交互态）；边 hover/选中令牌色=浏览器走查。
 *   - AC-13①（delta-r1 追加）：交互态节点 add 携带 ports（四向）或等效 magnet 配置、只读装配
 *     不携带；Graph.registerAnchor 注册的 SHAPE_CENTER_ANCHOR 回调对 node 桩返回绝对坐标
 *     （bbox 原点+图形区中心补偿：事件/网关按图形高 40/50 补偿、任务=整格中心）。
 *   - AC-2（delta-r1 追加）：事件/网关名称标签不得 text-overflow ellipsis 拼截断
 *     （overflow visible 或标签区宽不受节点宽约束）。
 *   - 走查附带（delta-r1）：Graph 构造参数含 autoResize 且指向画布容器（非布尔 true）。
 *   - AC-14（接线面）：blank:mousedown 平移监听（Graph API 自实现，无新依赖）；实际平移/框选降级=浏览器走查。
 *   - AC-11（回归面）：renderReadOnlyBpmn 共用 acme-bpmn-node 形状注册与 autoLayout 兜底（不叠 {0,0}）。
 *   - AC-3（接线面）：Transform 自由 resize 收口=Transform 插件从未实例化（变更清单「仅移除使用」）。
 *
 * 装配（X6 链路隔离照 REQ20261122102730-workflowPages 先例的库层变体）：x6-common polyfill 在
 * jsdom 收集期抛 Element is not defined → vi.mock('@antv/x6') 及全部插件包为桩；canvasFactory
 * 保持真实模块，经由 FakeGraph（Proxy 吸收任意 Graph 方法）捕获：Graph 构造参数（connecting 等）、
 * graph.on 事件注册、graph.bindKey 键绑定、插件实例化清单、react-shape register 参数。
 * 页面装配照 REQ20261122023441-workflowPages 先例：fetch stubGlobal 精确 pathname 路由 +
 * useMeStore 权限播种 + <AntApp> 包裹 + matchMedia/ResizeObserver 桩 + MemoryRouter
 * （/workflow/designer/:definitionId? base-path 单实例页签）。
 * 交互防坑（pitfalls 卡 7/8/10/13/14）：文案宽容匹配、结构节点定位、负例 contains。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { App as AntApp } from 'antd';
import { resolveCanvasPalette, useMeStore, type BpmnElementType, type BpmnModel } from '@acme/core';
import { BpmnNodeView, type BpmnNodeData } from '../src/pages/workflow/NodeShape';
import { Palette, BPMN_DND_MIME } from '../src/pages/workflow/Palette';
import { ValidationPanel } from '../src/pages/workflow/ValidationPanel';
import { DesignerPage } from '../src/pages/workflow/DesignerPage';
import {
  BPMN_NODE_SHAPE,
  createBpmnCanvas,
  ensureShapeRegistered,
  renderReadOnlyBpmn,
  syncModelToGraph,
} from '../src/pages/workflow/canvasFactory';
import { DEFAULT_NODE_SIZES, importModel } from '../src/pages/workflow/designerModel';

vi.setConfig({ testTimeout: 45000 });

// ————— X6 库层桩（hoisted 状态 + Proxy FakeGraph，捕获公开装配面） —————

const x6 = vi.hoisted(() => {
  const state = {
    graphCtor: [] as Array<Record<string, unknown>>,
    usedPlugins: [] as string[],
    listeners: {} as Record<string, number>,
    bindKeys: [] as Array<unknown[]>,
    registered: [] as Array<Record<string, unknown>>,
    transformInstantiated: 0,
    calls: [] as Array<{ name: string; args: unknown[] }>,
    cells: [] as Array<Record<string, unknown>>,
    cellById: new Map<string, Record<string, unknown>>(),
    /** delta-r1：节点 add 装配元数据捕获（addNode/addCell 首参，ports/magnet 断言观测面）。 */
    nodeAddMeta: [] as Array<Record<string, unknown>>,
    /** delta-r1：fromJSON 整体装载的 cell 元数据捕获（只读装配负例观测面）。 */
    loadCellMeta: [] as Array<Record<string, unknown>>,
  };
  const reset = (): void => {
    state.graphCtor = [];
    state.usedPlugins = [];
    state.listeners = {};
    state.bindKeys = [];
    state.registered = [];
    state.transformInstantiated = 0;
    state.calls = [];
    state.cells = [];
    state.cellById = new Map();
    state.nodeAddMeta = [];
    state.loadCellMeta = [];
  };  const pluginClass = (name: string): new () => unknown =>
    class {
      public constructor() {
        state.usedPlugins.push(name);
      }
    };
  /** react-shape 注册累积日志（ensureShapeRegistered 是模块级单例，reset 不清空）。 */
  const registerAll: Array<Record<string, unknown>> = [];
  /** delta-r1：Graph.registerAnchor 注册捕获（锚点可能在模块级/装配期注册，同 registerAll 口径持久）。 */
  const anchors: Array<{ name: unknown; fn: unknown }> = [];
  /** delta-r1：Graph.registerNode/registerEdge 静态注册捕获（持久，防新增静态注册被静默吸收）。 */
  const staticRegisters: Array<{ api: string; args: unknown[] }> = [];
  /** 单元格桩：记录 id/data/position/size（只读渲染与节点同步的观测面）；未知方法经 Proxy 吸收。 */
  const makeCell = (opts: Record<string, unknown> = {}, isEdge = false): Record<string, unknown> => {
    let data: Record<string, unknown> = (opts.data as Record<string, unknown>) ?? {};
    let pos = (opts.position as { x: number; y: number } | undefined)
      ?? { x: (opts.x as number) ?? 0, y: (opts.y as number) ?? 0 };
    const known: Record<string, unknown> = {
      id: opts.id,
      shape: opts.shape,
      __isEdge: isEdge,
      isNode: () => !isEdge,
      isEdge: () => isEdge,
      getData: () => data,
      setData: (patch: unknown): Record<string, unknown> => {
        if (typeof patch === 'object' && patch !== null) {
          data = { ...data, ...(patch as Record<string, unknown>) };
        }
        return cell;
      },
      position: (...args: unknown[]): unknown => {
        if (args.length >= 2) {
          pos = { x: args[0] as number, y: args[1] as number };
          return cell;
        }
        return pos;
      },
      size: () => ({ width: (opts.width as number) ?? 0, height: (opts.height as number) ?? 0 }),
      setSize: (): Record<string, unknown> => cell,
      attr: () => undefined,
      setLabels: (...args: unknown[]): unknown => {
        // delta-r2：既有边更新路径捕获（syncModelToGraph 对既有边走 cell.setLabels）
        state.calls.push({ name: 'cell.setLabels', args });
        return undefined;
      },
      prop: () => undefined,
    };
    const cell: Record<string, unknown> = new Proxy(known, {
      get(target, prop: string | symbol) {
        const key = String(prop);
        if (key in target) return target[key];
        return (..._args: unknown[]) => undefined;
      },
      has: () => true,
    });
    state.cells.push(cell);
    if (typeof opts.id === 'string' && opts.id !== '') {
      state.cellById.set(opts.id, cell);
    }
    return cell;
  };
  /** Graph 桩：Proxy 吸收任意方法；构造参数/事件/按键/插件/单元全部入 state。 */
  class FakeGraph {
    public constructor(options: Record<string, unknown>) {
      state.graphCtor.push(options ?? {});
      const methods: Record<string, (...args: unknown[]) => unknown> = {};
      const methodFor = (name: string): (...args: unknown[]) => unknown => {
        const record = (...args: unknown[]): unknown => {
          state.calls.push({ name, args });
          if (name === 'use') return undefined;
          if (name === 'on' || name === 'once') {
            const ev = String(args[0]);
            state.listeners[ev] = (state.listeners[ev] ?? 0) + 1;
            return undefined;
          }
          if (name === 'bindKey') {
            state.bindKeys.push(args);
            return undefined;
          }
          if (name === 'addNode') {
            state.nodeAddMeta.push((args[0] ?? {}) as Record<string, unknown>);
            return makeCell((args[0] ?? {}) as Record<string, unknown>, false);
          }
          if (name === 'addEdge' || name === 'createEdge') return makeCell((args[0] ?? {}) as Record<string, unknown>, true);
          if (name === 'addCell') {
            state.nodeAddMeta.push((args[0] ?? {}) as Record<string, unknown>);
            return makeCell((args[0] ?? {}) as Record<string, unknown>);
          }
          // fromJSON({cells:[...]}) 整体装载：落账每个 cell 元数据（只读渲染路径）
          if (name === 'fromJSON') {
            const cells = ((args[0] ?? {}) as { cells?: unknown[] }).cells ?? [];
            for (const meta of cells) {
              const m = (meta ?? {}) as Record<string, unknown>;
              const isEdge = m.shape === 'edge' || typeof m.source === 'object' || typeof m.target === 'object';
              state.loadCellMeta.push(m);
              makeCell(m, isEdge);
            }
            return undefined;
          }
          // batchUpdate(回调) 语义：回调必须真执行（节点/边同步在其内进行）
          if (/^(batchUpdate|batch|transaction)$/.test(name) && typeof args[0] === 'function') {
            return (args[0] as () => unknown)();
          }
          if (/^(getCellById|getCell|getCellByUUID)$/.test(name)) {
            return typeof args[0] === 'string' ? (state.cellById.get(args[0]) ?? undefined) : undefined;
          }
          if (/^hasCell$/.test(name)) return false;
          if (/^(getNodes|getEdges|getCells|getChildren|getSelectedCells|getRootCells)$/.test(name)) return [];
          if (/^(options|getOptions)$/.test(name)) return {};
          if (/^(getZoom|zoom)$/.test(name)) return 1;
          if (/^(getTranslate|translate)$/.test(name)) return { x: 0, y: 0 };
          return undefined;
        };
        return record;
      };
      const target: Record<string, unknown> = {};
      return new Proxy(target, {
        get(_t, prop: string | symbol) {
          const key = String(prop);
          if (!(key in methods)) methods[key] = methodFor(key);
          return methods[key];
        },
        has: () => true,
      }) as unknown as FakeGraph;
    }
  }
  return { state, reset, registerAll, anchors, staticRegisters, pluginClass, FakeGraph };
});

vi.mock('@antv/x6', () => {
  /** delta-r1：Point 最小桩（锚点回调以 Point 构造返回值；断言只读 x/y，不钉 X6 几何 API 面）。 */
  class PointStub {
    public x: number;
    public y: number;
    public constructor(x?: unknown, y?: unknown) {
      this.x = Number(x ?? 0);
      this.y = Number(y ?? 0);
    }
    public static create(x?: unknown, y?: unknown): PointStub {
      return new PointStub(x, y);
    }
    public round(): PointStub {
      this.x = Math.round(this.x);
      this.y = Math.round(this.y);
      return this;
    }
    public translate(dx?: unknown, dy?: unknown): PointStub {
      this.x += Number(dx ?? 0);
      this.y += Number(dy ?? 0);
      return this;
    }
    public equals(p?: { x?: unknown; y?: unknown } | null): boolean {
      return Number(p?.x) === this.x && Number(p?.y) === this.y;
    }
    public distance(p?: { x?: unknown; y?: unknown } | null): number {
      return Math.hypot(Number(p?.x ?? 0) - this.x, Number(p?.y ?? 0) - this.y);
    }
  }
  return {
    // Graph 静态面经 Proxy 吸收，防实现新增静态注册时桩崩；registerAnchor/registerNode/registerEdge
    // 落账捕获（delta-r1：锚点/注册装配面的观测面扩展，不改断言语义）
    Graph: new Proxy(x6.FakeGraph, {
      get(target, prop: string | symbol) {
        if (prop in target) return Reflect.get(target, prop);
        return (...args: unknown[]) => {
          const api = String(prop);
          if (api === 'registerAnchor') {
            x6.anchors.push({ name: args[0], fn: args[1] });
            return undefined;
          }
          if (/^register(Node|Edge|View)$/.test(api)) {
            x6.staticRegisters.push({ api, args });
          }
          return undefined;
        };
      },
    }),
    Point: PointStub,
  };
});
vi.mock('@antv/x6-react-shape', () => ({
  register: (opts: Record<string, unknown>) => {
    x6.state.registered.push(opts);
    x6.registerAll.push(opts);
  },
}));
vi.mock('@antv/x6-plugin-transform', () => ({
  Transform: class {
    public constructor() {
      x6.state.transformInstantiated += 1;
    }
  },
}));
vi.mock('@antv/x6-plugin-selection', () => ({ Selection: x6.pluginClass('Selection') }));
vi.mock('@antv/x6-plugin-snapline', () => ({ Snapline: x6.pluginClass('Snapline') }));
vi.mock('@antv/x6-plugin-clipboard', () => ({ Clipboard: x6.pluginClass('Clipboard') }));
vi.mock('@antv/x6-plugin-keyboard', () => ({ Keyboard: x6.pluginClass('Keyboard') }));
vi.mock('@antv/x6-plugin-minimap', () => ({ MiniMap: x6.pluginClass('MiniMap') }));

// ————— 公共装配（照 023441/102730 先例） —————

const LIST_PERM = 'workflow:definition:list';
const DEPLOY_PERM = 'workflow:definition:deploy';
const PERMS_ALL = [LIST_PERM, DEPLOY_PERM];

const DEF_9001 = {
  id: '9001', key: 'leave-approval', name: '请假审批', version: '3', status: 'DRAFT',
  bpmnXml: null, layoutJson: null, createdAt: '2026-11-21T02:00:00Z', deployedAt: null,
};

const meUser = {
  id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
  status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
};

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const stubBrowserGaps = () => {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: false, media: query, onchange: null,
    addListener: vi.fn(), removeListener: vi.fn(),
    addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
  }));
  vi.stubGlobal('ResizeObserver', class {
    observe() {}
    unobserve() {}
    disconnect() {}
  });
};

const seedMe = async (permissions: string[]): Promise<void> => {
  vi.stubGlobal('fetch', vi.fn(async () => envelope({
    user: meUser, roles: ['super_admin'], permissions, menus: [],
  })));
  await useMeStore.getState().load();
};

/** 设计器端点（详情 GET / 保存 PUT xml / 部署 POST deploy）+ me 精确 pathname 路由。 */
const setupDesignerApi = (): void => {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' || input instanceof URL
      ? new URL(String(input), 'http://localhost')
      : new URL((input as Request).url);
    const p = url.pathname;
    const method = (init?.method ?? (typeof input === 'object' && input !== null && 'method' in (input as Request) ? (input as Request).method : 'GET') ?? 'GET').toUpperCase();
    if (p === '/admin/v1/system/me') {
      return envelope({ user: meUser, roles: ['super_admin'], permissions: PERMS_ALL, menus: [] });
    }
    if (p === '/admin/v1/workflow/process-definitions/9001' && method === 'GET') {
      return envelope(DEF_9001);
    }
    if (p === '/admin/v1/workflow/process-definitions/9001/xml' && method === 'PUT') {
      return envelope({ ...DEF_9001, bpmnXml: '<definitions/>' });
    }
    if (p === '/admin/v1/workflow/process-definitions/9001/deploy' && method === 'POST') {
      return envelope({ ...DEF_9001, status: 'DEPLOYED', deployedAt: '2026-11-24T10:00:00Z' });
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
};

const renderDesigner = async (permissions: string[] = PERMS_ALL): Promise<HTMLElement> => {
  await seedMe(permissions);
  setupDesignerApi();
  const { container } = render(
    <AntApp>
      <MemoryRouter initialEntries={['/workflow/designer/9001']}>
        <DesignerPage />
      </MemoryRouter>
    </AntApp>,
  );
  await waitFor(() => {
    expect(screen.getByText('请假审批'), '设计器应加载定义并显示流程名').toBeTruthy();
  });
  return container;
};

beforeEach(() => {
  stubBrowserGaps();
  x6.reset();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
  useMeStore.getState().clear();
});

// ————— AC-1：BpmnNodeView 根 div 尺寸（node.size() 口径 + null 回退 + 禁 100%） —————

describe('REQ20261124224523 节点视图尺寸（AC-1）', () => {
  /** X6 Node 公开面桩：size()/getSize()/prop('size') 多口径提供像素尺寸（实现取哪个口径均命中）。 */
  const stubNode = (data: Partial<BpmnNodeData>, size = { width: 120, height: 64 }): never =>
    ({
      size: () => size,
      getSize: () => size,
      prop: (key: string) => (key === 'size' ? { ...size } : undefined),
      getData: () => ({ ...data }),
    }) as never;

  const rootOf = (): HTMLElement => {
    const el = document.querySelector<HTMLElement>('[data-bpmn-node]');
    expect(el, 'BpmnNodeView 根节点应带 data-bpmn-node').toBeTruthy();
    return el as HTMLElement;
  };

  it('should_match_root_div_to_node_pixel_size_when_node_prop_given', () => {
    render(<BpmnNodeView node={stubNode({ type: 'UserTask', name: '审批' })} />);
    const root = rootOf();
    expect(root.style.width, '根 div 宽=node prop 像素值').toBe('120px');
    expect(root.style.height, '根 div 高=node prop 像素值').toBe('64px');
    expect(root.style.height, '禁 height:100%（Chromium foreignObject bug 根因）').not.toBe('100%');
    expect(root.textContent).toContain('审批');
  });

  it('should_fall_back_to_default_pixel_size_when_node_prop_null', () => {
    // node prop 可空（独立渲染）：回退默认像素值，仍禁 100%
    render(<BpmnNodeView node={null as never} />);
    const root = rootOf();
    const width = Number.parseFloat(root.style.width);
    const height = Number.parseFloat(root.style.height);
    expect(Number.isFinite(width) && width > 0, `回退宽度应为正像素值（实际 ${root.style.width}）`).toBe(true);
    expect(Number.isFinite(height) && height > 0, `回退高度应为正像素值（实际 ${root.style.height}）`).toBe(true);
    expect(root.style.width).not.toBe('100%');
    expect(root.style.height).not.toBe('100%');
  });
});

// ————— AC-2：BpmnNodeView 语义形状（SVG 形态）+ 标签下方 + 令牌面 —————

describe('REQ20261124224523 节点语义形状与令牌（AC-2 组件面）', () => {
  /** X6 Node 公开面桩（同 AC-1：多口径尺寸 + data 注入）。 */
  const stubNode = (data: Partial<BpmnNodeData>, size = { width: 120, height: 64 }): never =>
    ({
      size: () => size,
      getSize: () => size,
      prop: (key: string) => (key === 'size' ? { ...size } : undefined),
      getData: () => ({ ...data }),
    }) as never;

  const rootOf = (): HTMLElement => {
    const el = document.querySelector<HTMLElement>('[data-bpmn-node]');
    expect(el, 'BpmnNodeView 根节点应带 data-bpmn-node').toBeTruthy();
    return el as HTMLElement;
  };

  it('should_render_event_as_circle_when_start_event_shown', () => {
    // 事件视觉=圆形（AC-2/确认③）：存在 border-radius:50% 圆形图形元素，无菱形旋转形态
    render(<BpmnNodeView node={stubNode({ type: 'StartEvent', name: '开始' })} />);
    const root = rootOf();
    expect(circleIn(root), 'StartEvent 应渲染圆形图形（border-radius 50% 形态）').toBeTruthy();
    expect(diamondIn(root), '事件不应渲染菱形').toBeNull();
    expect(root.textContent).toContain('开始');
  });

  it('should_render_gateway_as_diamond_when_xor_shown', () => {
    // 网关视觉=菱形（AC-2/确认③）：存在 rotate(45deg) 旋转方形形态，无圆
    render(<BpmnNodeView node={stubNode({ type: 'XOR', name: '网关' })} />);
    const root = rootOf();
    expect(diamondIn(root), 'XOR 应渲染菱形（rotate 45° 形态）').toBeTruthy();
    expect(circleIn(root), '网关不应渲染圆形').toBeNull();
    expect(root.textContent).toContain('网关');
  });

  it('should_render_task_as_rounded_rect_when_user_task_shown', () => {
    // 任务/子流程=圆角矩形卡片（AC-2）：圆角卡片形态且无圆/菱形主图形
    render(<BpmnNodeView node={stubNode({ type: 'UserTask', name: '审批' })} />);
    const root = rootOf();
    expect(roundedCardIn(root), '任务应有圆角卡片形态').toBeTruthy();
    expect(circleIn(root), '任务不应渲染圆形图形').toBeNull();
    expect(diamondIn(root), '任务不应渲染菱形图形').toBeNull();
  });

  it('should_render_boundary_event_as_dashed_ring_when_boundary_shown', () => {
    // 确认③：边界事件=虚线圆环（圆形 + 虚线描边形态）
    render(<BpmnNodeView node={stubNode({ type: 'BoundaryEvent', name: '超时' })} />);
    const root = rootOf();
    const circle = circleIn(root);
    expect(circle, 'BoundaryEvent 应渲染圆形图形').toBeTruthy();
    expect(circle?.getAttribute('style') ?? '', 'BoundaryEvent 圆形应为虚线描边（双圈语义形态）').toMatch(/dashed/);
  });

  it('should_place_name_label_below_shape_when_event_rendered', () => {
    // 事件/网关名称标签位于图形下方（AC-2/钉死事实 2）：标签元素在 DOM 中后于图形元素
    render(<BpmnNodeView node={stubNode({ type: 'StartEvent', name: '开始' })} />);
    const root = rootOf();
    const shape = circleIn(root);
    expect(shape, '图形元素应存在').toBeTruthy();
    const label = [...root.querySelectorAll('span')].find((el) => el.textContent === '开始');
    expect(label, '名称标签元素应存在且完整含名称文本').toBeTruthy();
    expect(Boolean(shape && label && (shape.compareDocumentPosition(label) & Node.DOCUMENT_POSITION_FOLLOWING)),
      '标签应在图形之后（图形下方完整可读）').toBe(true);
  });

  it('should_use_token_variables_without_hardcoded_hex_when_rendered', () => {
    // 色值全走令牌（AC-2/钉死事实 2）：独立渲染（未注入画布调色板）时全部色值走 var(--) / 令牌引用，无 hex
    for (const type of ['StartEvent', 'XOR', 'UserTask', 'EmbeddedSubProcess', 'BoundaryEvent'] as BpmnElementType[]) {
      const { container, unmount } = render(
        <BpmnNodeView node={stubNode({ type, name: '令牌' })} />,
      );
      const html = container.innerHTML;
      expect(html, `${type} 渲染不得出现硬编码 hex 色值`).not.toMatch(/#[0-9a-fA-F]{6}\b/);
      expect(html, `${type} 渲染应引用 CSS 变量令牌`).toContain('var(--');
      unmount();
    }
  });
});

/** 黑盒图形形态识别（CSS 形态：圆=borderRadius 50%；菱形=rotate 45°；卡片=非 50% 圆角）。 */
const circleIn = (root: HTMLElement): HTMLElement | null =>
  [...root.querySelectorAll<HTMLElement>('*')].find((el) => el.style.borderRadius === '50%') ?? null;

const diamondIn = (root: HTMLElement): HTMLElement | null =>
  [...root.querySelectorAll<HTMLElement>('*')].find((el) => el.style.transform.includes('rotate(45deg)')) ?? null;

const roundedCardIn = (root: HTMLElement): HTMLElement | null =>
  [...root.querySelectorAll<HTMLElement>('*')].find((el) => {
    const radius = el.style.borderRadius;
    return radius !== '' && radius !== '0px' && radius !== '50%';
  }) ?? null;

// ————— AC-2/AC-9：Palette 分组、白名单条目、DnD MIME、令牌色 —————

describe('REQ20261124224523 元素面板（AC-2/AC-9）', () => {
  it('should_render_three_group_titles_when_palette_rendered', () => {
    // 分组小标题三组（钉死事实 7）：事件/网关/活动（zh 组名=分组键文案，精确匹配避开条目名）
    render(<AntApp><Palette /></AntApp>);
    const palette = screen.getByTestId('bpmn-palette');
    expect(palette.querySelector('*'), '面板应有内容').toBeTruthy();
    expect(screen.getByText('事件', { exact: true }), '分组标题「事件」应渲染').toBeTruthy();
    expect(screen.getByText('网关', { exact: true }), '分组标题「网关」应渲染').toBeTruthy();
    expect(screen.getByText('活动', { exact: true }), '分组标题「活动」应渲染').toBeTruthy();
  });

  it('should_expose_whitelist_palette_items_when_palette_rendered', () => {
    // 白名单 8 元素条目（容器 testid 钉死事实 1 回归）
    render(<AntApp><Palette /></AntApp>);
    for (const type of ['StartEvent', 'EndEvent', 'XOR', 'AND', 'UserTask', 'ServiceTask', 'EmbeddedSubProcess', 'BoundaryEvent'] as BpmnElementType[]) {
      expect(screen.getByTestId(`palette-item-${type}`), `palette-item-${type} 应渲染`).toBeTruthy();
    }
  });

  it('should_set_acme_dnd_mime_when_palette_item_dragged', () => {
    // 既有行为回归（钉死事实 7）：拖拽 dataTransfer 设 MIME text/acme-bpmn-element
    render(<AntApp><Palette /></AntApp>);
    const item = screen.getByTestId('palette-item-StartEvent');
    const setData = vi.fn();
    fireEvent.dragStart(item, { dataTransfer: { setData } });
    expect(setData, `应设置 MIME ${BPMN_DND_MIME}`).toHaveBeenCalled();
    const [mime, payload] = setData.mock.calls[0] as unknown[];
    expect(mime).toBe(BPMN_DND_MIME);
    expect(String(payload), '拖拽载荷应携带元素类型').toContain('StartEvent');
  });

  it('should_color_palette_with_canvas_tokens_when_palette_rendered', () => {
    // 色值全走令牌（AC-2）：面板内 hex 色值必须 ∈ resolveCanvasPalette 令牌册（无离册第二套色）
    render(<AntApp><Palette /></AntApp>);
    const palette = screen.getByTestId('bpmn-palette');
    const allowed = new Set<string>([
      ...Object.values(resolveCanvasPalette('dark')),
      ...Object.values(resolveCanvasPalette('light')),
    ]);
    const hexes = [...palette.innerHTML.matchAll(/#[0-9a-fA-F]{6}\b/g)].map((m) => m[0]);
    for (const hex of hexes) {
      expect(allowed.has(hex), `面板色值 ${hex} 应为画布令牌（TONES/palette 单源）`).toBe(true);
    }
  });
});

// ————— AC-7：ValidationPanel 紧凑状态条 / fail 明细 / 定位回调 —————

describe('REQ20261124224523 校验面板（AC-7）', () => {
  it('should_render_compact_status_bar_when_status_idle', () => {
    // idle=一行紧凑状态条（钉死事实 5）：无 antd Empty 大块占位
    render(<AntApp><ValidationPanel status="idle" items={[]} /></AntApp>);
    const panel = screen.getByTestId('validation-panel');
    expect(panel).toBeTruthy();
    expect(panel.querySelector('.ant-empty'), 'idle 态不得渲染 Empty 占位').toBeNull();
  });

  it('should_render_compact_status_bar_when_status_pass', () => {
    // pass 同口径（AC-7）：紧凑状态条 + 无 Empty；通过文案族回归（023441 先例）
    render(<AntApp><ValidationPanel status="pass" items={[]} /></AntApp>);
    const panel = screen.getByTestId('validation-panel');
    expect(panel.querySelector('.ant-empty'), 'pass 态不得渲染 Empty 占位').toBeNull();
    expect(screen.getAllByText(/静态校验通过/).length, '通过提示文案（标签/正文宽容多命中）').toBeGreaterThanOrEqual(1);
  });

  it('should_render_items_with_node_id_and_message_when_status_fail', () => {
    // fail：列表逐条 nodeId+message（钉死事实 5）
    render(<AntApp><ValidationPanel status="fail" items={[
      { nodeId: 'task-1', message: '用户任务缺少指派' },
      { nodeId: 'end', message: '结束事件不可有出边' },
    ]} /></AntApp>);
    expect(screen.getByText(/task-1/)).toBeTruthy();
    expect(screen.getByText('用户任务缺少指派')).toBeTruthy();
    expect(screen.getByText(/end/)).toBeTruthy();
    expect(screen.getByText('结束事件不可有出边')).toBeTruthy();
  });

  it('should_invoke_on_locate_when_fail_item_clicked', async () => {
    const onLocate = vi.fn();
    render(<AntApp><ValidationPanel status="fail" items={[
      { nodeId: 'task-1', message: '用户任务缺少指派' },
    ]} onLocate={onLocate} /></AntApp>);
    // 点击条目触发 onLocate(nodeId)（定位按钮 aria-label=定位；nodeId=code 形态）
    const panel = screen.getByTestId('validation-panel');
    expect(panel.textContent).toContain('用户任务缺少指派');
    const row = panel.querySelector('code')?.closest('li') as HTMLElement | null;
    expect(row, 'fail 条目应可定位到行容器').toBeTruthy();
    expect((row as HTMLElement).querySelector('code')?.textContent, '条目应含 nodeId（code 形态）').toBe('task-1');
    fireEvent.click(within(row as HTMLElement).getByRole('button', { name: /定\s*位/ }));
    await waitFor(() => {
      expect(onLocate).toHaveBeenCalledWith('task-1');
    });
    expect(onLocate).toHaveBeenCalledTimes(1);
  });

  it('should_cap_fail_list_scroll_when_many_items_rendered', () => {
    // fail/backend 列表内滚有上限（AC-7）：滚动容器存在 maxHeight 上限 + 滚动语义
    render(<AntApp><ValidationPanel status="fail" items={Array.from({ length: 20 }, (_, i) => ({
      nodeId: `n${i}`, message: `问题 ${i}`,
    }))} /></AntApp>);
    const panel = screen.getByTestId('validation-panel');
    expect(panel.textContent).toContain('问题 0');
    // 内滚上限载体允许为面板根或列表容器（滚动容器应有 maxHeight 上限 + 滚动语义）
    const candidates = [panel, ...panel.querySelectorAll<HTMLElement>('ul,ol')];
    const scroller = candidates.find((el) => {
      const max = parseFloat(el.style.maxHeight);
      return Number.isFinite(max) && max > 0 && ['auto', 'scroll'].includes(el.style.overflowY);
    });
    expect(scroller, `fail 列表内滚应有 maxHeight 上限 + overflow 滚动（面板=${panel.style.maxHeight}）`).toBeTruthy();
  });
});

// ————— AC-3/AC-13/AC-14/AC-11：canvasFactory X6 装配接线面（FakeGraph 观测） —————

describe('REQ20261124224523 画布装配接线（AC-3/AC-13/AC-14 接线面）', () => {
  const createCanvas = (opts: { readOnly?: boolean; minimap?: boolean } = {}): void => {
    const container = document.createElement('div');
    createBpmnCanvas({
      container,
      minimapContainer: opts.minimap ? document.createElement('div') : null,
      palette: resolveCanvasPalette('dark'),
      readOnly: opts.readOnly,
      handlers: {
        onBlankClick: () => {},
        onCellClick: () => {},
        onNodeMoved: () => {},
        onEdgeConnected: () => 'keep',
      },
    });
  };

  it('should_register_acme_bpmn_node_shape_when_canvas_created', () => {
    // 钉死事实 1：节点形状注册名 acme-bpmn-node，视图组件=BpmnNodeView
    expect(BPMN_NODE_SHAPE, '注册名钉死').toBe('acme-bpmn-node');
    ensureShapeRegistered();
    const registration = x6.registerAll.find((r) => r.shape === BPMN_NODE_SHAPE);
    expect(registration, 'ensureShapeRegistered 应注册 acme-bpmn-node').toBeTruthy();
    expect(registration?.component, '注册视图组件应为 BpmnNodeView').toBe(BpmnNodeView);
  });

  it('should_stop_edges_at_node_boundary_when_canvas_created', () => {
    // AC-13①/确认②：connectionPoint=boundary（连线端点止于节点边界，箭头不没入节点）
    createCanvas();
    const options = x6.state.graphCtor.at(-1);
    expect(options, 'createBpmnCanvas 应构造 Graph').toBeTruthy();
    const connecting = options?.connecting as Record<string, unknown> | undefined;
    expect(connecting, 'Graph 构造参数应含 connecting').toBeTruthy();
    expect(connecting?.connectionPoint, 'connectionPoint 应为 boundary').toBe('boundary');
  });

  it('should_never_instantiate_transform_plugin_when_canvas_created', () => {
    // AC-3/变更清单「仅移除使用」：交互态与只读态都不得实例化 Transform（自由 resize 收口）
    createCanvas({ readOnly: false });
    createCanvas({ readOnly: true });
    expect(x6.state.transformInstantiated, 'Transform 插件不应被实例化').toBe(0);
  });

  it('should_enable_blank_drag_pan_when_canvas_interactive', () => {
    // AC-14/确认④：画布平移=空白处按住拖动（Graph pan 装配接线；实际平移手感=浏览器走查）
    createCanvas({ readOnly: false });
    const options = x6.state.graphCtor.at(-1);
    expect(options?.panning, '应启用画布空白拖拽平移（panning 装配）').toBeTruthy();
  });

  it('should_bind_delete_key_for_removal_when_canvas_interactive', () => {
    // AC-13④/确认②：Delete 键删除选中节点/边（键绑定接线；删除行为=浏览器走查）
    createCanvas({ readOnly: false });
    const bound = x6.state.bindKeys.map((args) => JSON.stringify(args[0])).join('|');
    expect(bound, '应绑定 Delete 键（delete/backspace）').toMatch(/delete/i);
  });

  it('should_register_minimap_plugin_when_minimap_container_given', () => {
    // AC-6 接线面：提供 minimap 容器时应实例化 MiniMap 插件（主题化背景=global.css 覆盖，见模型面）
    createCanvas({ minimap: true });
    expect(x6.state.usedPlugins, 'MiniMap 插件应被实例化').toContain('MiniMap');
  });

  it('should_render_readonly_canvas_with_same_node_shape_when_detail_renders', () => {
    // AC-11 回归面：只读渲染共用 acme-bpmn-node 形状 + 布局缺省 autoLayout 兜底（不叠 {0,0}）
    const graph = new x6.FakeGraph({});
    const xml = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
  <process id="p1">
    <startEvent id="start"/>
    <sequenceFlow id="f0" sourceRef="start" targetRef="task-1"/>
    <userTask id="task-1" name="只读任务"/>
    <sequenceFlow id="f1" sourceRef="task-1" targetRef="end"/>
    <endEvent id="end"/>
  </process>
</definitions>`;
    renderReadOnlyBpmn(graph as never, { bpmnXml: xml, layoutJson: null, activeNodeIds: new Set(['task-1']) }, resolveCanvasPalette('dark'), true, {
      nameOf: () => '',
      summaryOf: () => undefined,
    });
    const model: BpmnModel = importModel(xml);
    const nodeCells = x6.state.cells.filter((c) => c.__isEdge === false);
    const shaped = nodeCells.filter((c) => c.shape === BPMN_NODE_SHAPE);
    expect(shaped.length, '只读渲染节点应共用 acme-bpmn-node 形状').toBeGreaterThanOrEqual(model.nodes.length);
    // 布局缺省兜底：节点位置非全 {0,0}（AC-4 只读面回归）
    const positions = new Set(nodeCells.map((c) => JSON.stringify((c.position as () => { x: number; y: number })())));
    expect(positions.size, '只读节点不应全部叠于同点').toBeGreaterThan(1);
  });
});

// ————— delta-r1：AC-13① 连线锚点装配（ports/magnet + anchor 绝对坐标）+ autoResize 构造面 —————

describe('REQ20261124224523 连线锚点装配（delta-r1：AC-13① 交互前提）', () => {
  /** ports（四向）或等效 magnet 起线配置识别：ports.items ≥4 / ports.groups ≥4 / 任意层级 magnet 键。 */
  const deepHasKey = (value: unknown, key: string): boolean => {
    if (Array.isArray(value)) return value.some((v) => deepHasKey(v, key));
    if (value !== null && typeof value === 'object') {
      return Object.entries(value as Record<string, unknown>)
        .some(([k, v]) => k === key || deepHasKey(v, key));
    }
    return false;
  };
  const directionalPortCount = (meta: Record<string, unknown>): number => {
    const ports = meta.ports as { items?: unknown[]; groups?: Record<string, unknown> } | undefined;
    if (ports === null || typeof ports !== 'object') return 0;
    if (Array.isArray(ports.items) && ports.items.length >= 4) return ports.items.length;
    if (ports.groups !== null && typeof ports.groups === 'object') return Object.keys(ports.groups).length;
    return 0;
  };
  /** 任意属性访问不炸的哑对象（锚点回调磁贴/视图侧参数的容忍桩，非断言面）。 */
  const absorber = (): Record<string, unknown> => new Proxy({}, {
    get: () => (..._args: unknown[]) => undefined,
    has: () => true,
  });

  /** 含节点的定义 XML（交互态模型同步与只读装配共用同一解析口径，沿用只读面已验证的 XML 形状）。 */
  const MODEL_XML = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
  <process id="p1">
    <startEvent id="start"/>
    <sequenceFlow id="f0" sourceRef="start" targetRef="task-1"/>
    <userTask id="task-1" name="审批"/>
    <sequenceFlow id="f1" sourceRef="task-1" targetRef="end"/>
    <endEvent id="end"/>
  </process>
</definitions>`;

  /** 交互态 designer 装配（定义携带 bpmnXml → 模型同步产生节点 add；DnD 落点几何仍=浏览器走查）。 */
  const renderDesignerWithModel = async (): Promise<void> => {
    await seedMe(PERMS_ALL);
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' || input instanceof URL
        ? new URL(String(input), 'http://localhost')
        : new URL((input as Request).url);
      const method = (init?.method ?? 'GET').toUpperCase();
      if (url.pathname === '/admin/v1/system/me') {
        return envelope({ user: meUser, roles: ['super_admin'], permissions: PERMS_ALL, menus: [] });
      }
      if (url.pathname === '/admin/v1/workflow/process-definitions/9001' && method === 'GET') {
        return envelope({ ...DEF_9001, bpmnXml: MODEL_XML });
      }
      return envelope(null);
    }));
    render(
      <AntApp>
        <MemoryRouter initialEntries={['/workflow/designer/9001']}>
          <DesignerPage />
        </MemoryRouter>
      </AntApp>,
    );
    await waitFor(() => {
      expect(screen.getByText('请假审批'), '设计器应加载定义并显示流程名').toBeTruthy();
    });
  };

  /** 画布装配（canvasFactory 真实模块；容器外置以便 autoResize 观测）。 */
  const assembleCanvas = (container: HTMLElement = document.createElement('div')): HTMLElement => {
    createBpmnCanvas({
      container,
      minimapContainer: null,
      palette: resolveCanvasPalette('dark'),
      readOnly: false,
      handlers: {
        onBlankClick: () => {},
        onCellClick: () => {},
        onNodeMoved: () => {},
        onEdgeConnected: () => 'keep',
      },
    });
    return container;
  };

  it('should_assemble_edge_start_anchor_ports_when_node_added_interactively', async () => {
    // AC-13①交互前提（delta-r1 钉死口径）：交互态节点 add 装配携带可起线锚点面——
    // ports（四向）或等效 magnet 配置；readOnly 装配不携带（负例见下一用例）。
    // 落点几何仍=浏览器走查（AC-10），此处只钉装配元数据。
    await renderDesignerWithModel();
    await waitFor(() => {
      expect(
        [...x6.state.nodeAddMeta, ...x6.state.loadCellMeta].some((meta) => meta.shape === BPMN_NODE_SHAPE),
        '交互态模型同步应产生 acme-bpmn-node 节点 add',
      ).toBe(true);
    });
    const added = [...x6.state.nodeAddMeta, ...x6.state.loadCellMeta]
      .filter((meta) => meta.shape === BPMN_NODE_SHAPE);
    expect(
      added.some((meta) => directionalPortCount(meta) >= 4 || deepHasKey(meta, 'magnet')),
      `交互态节点 add 元数据应携带 ports（四向）或等效 magnet 起线配置（捕获 ${added.length} 个节点装配）`,
    ).toBe(true);
  });

  it('should_not_carry_edge_start_anchor_ports_when_readonly_assembled', () => {
    // AC-13①负例（delta-r1）：只读装配不携带起线锚点面（readOnly 不可交互连线；
    // 若 ports 挂在共用注册面，只读节点会连带携带——按 AC 语义应在交互态节点 add 装配）
    const graph = new x6.FakeGraph({});
    renderReadOnlyBpmn(graph as never, { bpmnXml: MODEL_XML, layoutJson: null, activeNodeIds: new Set(['task-1']) }, resolveCanvasPalette('dark'), true, {
      nameOf: () => '',
      summaryOf: () => undefined,
    });
    const readonlyNodes = [...x6.state.loadCellMeta, ...x6.state.nodeAddMeta]
      .filter((meta) => meta.shape === BPMN_NODE_SHAPE);
    expect(readonlyNodes.length, '只读渲染应产生 acme-bpmn-node 节点装配元数据').toBeGreaterThan(0);
    for (const meta of readonlyNodes) {
      expect(directionalPortCount(meta), `只读节点 ${String(meta.id)} 装配不得携带 ports 起线面`).toBe(0);
      expect(deepHasKey(meta, 'magnet'), `只读节点 ${String(meta.id)} 装配不得携带 magnet 起线配置`).toBe(false);
    }
  });

  it('should_return_absolute_shape_center_from_registered_anchor_when_node_given', () => {
    // AC-13①端点贴合（delta-r1 钉死口径）：Graph.registerAnchor 注册的 SHAPE_CENTER_ANCHOR
    // 回调，对给定 node（getPosition/getSize/getData/getBBox 桩）返回的点=绝对坐标——
    // bbox 原点 + 图形几何中心补偿：事件/网关用图形区高度补偿（图形高契约钉死 40/50），任务=整格中心
    assembleCanvas();
    ensureShapeRegistered();
    const matched = x6.anchors.find((a) => String(a.name) === 'SHAPE_CENTER_ANCHOR')
      ?? x6.anchors.find((a) => /shape[-_.]?center/i.test(String(a.name)));
    expect(matched, `应捕获 Graph.registerAnchor 注册的 SHAPE_CENTER_ANCHOR 回调（已捕获 ${x6.anchors.length} 个锚点注册）`).toBeTruthy();
    const anchorFn = matched?.fn;
    expect(typeof anchorFn, 'SHAPE_CENTER_ANCHOR 注册应携带回调函数').toBe('function');

    /** 锚点 node 桩（钉死公开面：getPosition/getSize/getData/getBBox；bbox 附带 Rectangle 常用面）。 */
    const anchorNode = (type: BpmnElementType): Record<string, unknown> => {
      const cellSize = DEFAULT_NODE_SIZES[type];
      const bbox = {
        x: 100, y: 200, width: cellSize.width, height: cellSize.height,
        left: 100, top: 200, right: 100 + cellSize.width, bottom: 200 + cellSize.height,
        origin: () => ({ x: 100, y: 200 }),
        center: () => ({ x: 100 + cellSize.width / 2, y: 200 + cellSize.height / 2 }),
        topLeft: () => ({ x: 100, y: 200 }),
        topRight: () => ({ x: 100 + cellSize.width, y: 200 }),
        bottomLeft: () => ({ x: 100, y: 200 + cellSize.height }),
        bottomRight: () => ({ x: 100 + cellSize.width, y: 200 + cellSize.height }),
      };
      const node: Record<string, unknown> = {
        isNode: () => true,
        isEdge: () => false,
        getPosition: () => ({ x: 100, y: 200 }),
        getSize: () => ({ width: cellSize.width, height: cellSize.height }),
        getData: () => ({ type, name: '锚点' }),
        getBBox: () => bbox,
      };
      const nodeProxy = new Proxy(node, {
        get(target, prop: string | symbol) {
          const key = String(prop);
          if (key in target) return target[key];
          if (key === 'cell') return nodeProxy; // 容忍 view.cell 调用约定（同一 node 观测面）
          return (..._args: unknown[]) => undefined;
        },
        has: () => true,
      });
      return nodeProxy;
    };
    /** 调用约定容忍（node 直传 / view.cell / this 绑定三口径）；断言语义只钉返回点坐标。 */
    const anchorPointAt = (type: BpmnElementType): { x: number; y: number } => {
      const node = anchorNode(type);
      const point = (anchorFn as (...args: unknown[]) => unknown).call(
        node, node, absorber(), node, 'source', {},
      ) as { x?: unknown; y?: unknown } | undefined;
      expect(point, `${type} 锚点回调应返回点对象`).toBeTruthy();
      return { x: Number((point as { x?: unknown })?.x), y: Number((point as { y?: unknown })?.y) };
    };
    // 图形区半高（契约背景/模型面钉死：事件图形 40、网关 50）；任务无条目 → 整格中心
    const graphicHalfHeight: Partial<Record<BpmnElementType, number>> = { StartEvent: 20, XOR: 25 };
    const expectAnchorAt = (type: BpmnElementType, why: string): void => {
      const point = anchorPointAt(type);
      expect(Number.isFinite(point.x) && Number.isFinite(point.y), `${type} 锚点应返回数值 {x,y}`).toBe(true);
      const cellSize = DEFAULT_NODE_SIZES[type];
      const offsetY = graphicHalfHeight[type] ?? cellSize.height / 2;
      expect(point.x, `${why}：x=图形几何中心水平补偿（绝对坐标）`).toBe(100 + cellSize.width / 2);
      expect(point.y, `${why}：y=bbox 原点+图形区中心补偿（绝对坐标）`).toBe(200 + offsetY);
    };
    expectAnchorAt('UserTask', '任务锚点应=整格中心');
    expectAnchorAt('StartEvent', '事件锚点应=bbox 原点+图形区中心补偿（图形高 40）');
    expectAnchorAt('XOR', '网关锚点应=bbox 原点+图形区中心补偿（图形高 50）');
  });

  it('should_observe_canvas_container_via_auto_resize_when_graph_created', () => {
    // 走查附带（delta-r1）：Graph 构造参数含 autoResize 且指向画布容器（非布尔 true）
    const container = assembleCanvas();
    const options = x6.state.graphCtor.at(-1);
    expect(options, 'createBpmnCanvas 应构造 Graph').toBeTruthy();
    const autoResize = options?.autoResize;
    expect(autoResize, 'Graph 构造应装配 autoResize（画布自适应容器尺寸变化）').toBeTruthy();
    expect(typeof autoResize, 'autoResize 应指向画布容器而非布尔 true').not.toBe('boolean');
    expect(autoResize, 'autoResize 缺省（未传包裹层时）指向 createBpmnCanvas 的画布容器').toBe(container);
  });
});

// ————— delta-r2：AC-13②③ 连线标记装配（defaultLabel 中和 + label markup/attrs 契约） —————

describe('REQ20261124224523 连线标记装配（delta-r2：AC-13②③ 接线面）', () => {
  /** label markup JSON 数组项的 selector 集（字符串 markup 在 X6 下 selectors 恒空=禁用形态）。 */
  const selectorsOf = (label: Record<string, unknown>): string[] => {
    const markup = label.markup as Array<{ selector?: string }> | undefined;
    return Array.isArray(markup) ? markup.map((item) => String(item?.selector ?? '')) : [];
  };

  /** 三种标记语义齐备的模型（条件流/默认流/边名称）。 */
  const LABEL_MODEL: BpmnModel = {
    nodes: [
      { id: 'start', type: 'StartEvent', name: '开始' },
      { id: 'task', type: 'UserTask', name: '审批' },
    ],
    edges: [
      { id: 'f-condition', sourceId: 'start', targetId: 'task', condition: '${amount > 1000}' },
      { id: 'f-default', sourceId: 'start', targetId: 'task', isDefault: true },
      { id: 'f-named', sourceId: 'start', targetId: 'task', name: '同意' },
    ],
  } as BpmnModel;

  const POSITIONS = new Map([
    ['start', { x: 40, y: 40 }],
    ['task', { x: 300, y: 40 }],
  ]);

  const syncLabels = (): void => {
    syncModelToGraph(new x6.FakeGraph({}) as never, LABEL_MODEL, POSITIONS, resolveCanvasPalette('dark'), false, {
      nameOf: (node) => node.name || '',
      summaryOf: () => undefined,
    });
  };

  const addEdgeMetas = (): Array<Record<string, unknown>> =>
    x6.state.calls.filter((call) => call.name === 'addEdge').map((call) => call.args[0] as Record<string, unknown>);

  it('should_neutralize_default_label_attrs_when_edge_added', () => {
    // delta-r2 修复1接线：模型边 addEdge 元数据携带 defaultLabel:{attrs:{}}——中和 X6 静态
    // Edge.defaultLabel.attrs（text/rect 键，rect 含 ref:'label'）经 AttrManager CSS 兜底
    // 匹配落到自定义标记元素、ref 解析失败抛 "reference does not exist" 的崩溃链
    // （真实渲染面回归见 designerEdgeLabels.test.tsx）。
    syncLabels();
    const metas = addEdgeMetas();
    expect(metas.length, '三条语义边应各产生一次 addEdge').toBe(LABEL_MODEL.edges.length);
    for (const meta of metas) {
      expect(meta.defaultLabel, `边 ${String(meta.id)} 应携带 defaultLabel`).toBeTruthy();
      expect((meta.defaultLabel as { attrs?: unknown }).attrs, 'defaultLabel.attrs 应为空对象（中和默认 attrs 合并污染）').toEqual({});
    }
  });

  it('should_carry_selector_keyed_json_markup_when_edge_labeled', () => {
    // label 契约（delta-r2 修复1）：markup=JSON 数组自带 selector（禁字符串——string markup
    // 在 X6 parseLabelStringMarkup 下 selectors 恒空，attrs 全丢）；attrs 键 ⊆ markup selectors
    // （CSS 兜底匹配不可依赖）；label 携带定位 distance。
    syncLabels();
    for (const meta of addEdgeMetas()) {
      const labels = (meta.labels ?? []) as Array<Record<string, unknown>>;
      for (const label of labels) {
        const selectors = selectorsOf(label);
        expect(Array.isArray(label.markup), `边 ${String(meta.id)} 的 label markup 应为 JSON 数组（禁字符串形态）`).toBe(true);
        expect(selectors.length > 0, `边 ${String(meta.id)} 的 markup 应自带 selector`).toBe(true);
        for (const key of Object.keys(label.attrs as Record<string, unknown>)) {
          expect(selectors, `边 ${String(meta.id)} 的 attrs 键 ${key} 应被 markup selector 覆盖`).toContain(key);
        }
        const distance = (label.position as { distance?: number } | undefined)?.distance;
        expect(typeof distance === 'number' && Number.isFinite(distance), `边 ${String(meta.id)} 的 label 应带定位 distance`).toBe(true);
      }
    }
  });

  it('should_refresh_selector_keyed_labels_when_existing_edge_updated', () => {
    // 既有边更新路径（属性面板防抖回写 → syncModelToGraph → cell.setLabels）同契约：
    // markup 数组 + attrs 键 ⊆ markup selectors（属性面板回写连线标记不丢的接线保障）。
    syncLabels();
    const updated: BpmnModel = {
      nodes: LABEL_MODEL.nodes,
      edges: LABEL_MODEL.edges.map((edge) => (edge.id === 'f-condition' ? { ...edge, condition: '${passed}' } : edge)),
    };
    syncModelToGraph(new x6.FakeGraph({}) as never, updated, POSITIONS, resolveCanvasPalette('dark'), false, {
      nameOf: (node) => node.name || '',
      summaryOf: () => undefined,
    });
    const setLabelsCalls = x6.state.calls.filter((call) => call.name === 'cell.setLabels');
    // 幂等重灌语义：syncModelToGraph 对每条既有边无条件 setLabels（三条边全部重灌）
    expect(setLabelsCalls.length, '既有边逐条走 cell.setLabels 重灌标记').toBe(LABEL_MODEL.edges.length);
    const labels = (setLabelsCalls[0].args[0] ?? []) as Array<Record<string, unknown>>;
    expect(labels.length, '更新后条件边应携带条件流标记').toBe(1);
    const label = labels[0];
    const selectors = selectorsOf(label);
    expect(Array.isArray(label.markup), 'setLabels 的 markup 应为 JSON 数组').toBe(true);
    for (const key of Object.keys(label.attrs as Record<string, unknown>)) {
      expect(selectors, `setLabels 的 attrs 键 ${key} 应被 markup selector 覆盖`).toContain(key);
    }
  });
});

// ————— delta-r1：AC-2 名称标签完整显示（事件/网关不得 ellipsis 拼截断） —————

describe('REQ20261124224523 名称标签完整显示（delta-r1：AC-2）', () => {
  /** X6 Node 公开面桩（同 AC-1/AC-2 组件面：多口径尺寸 + data 注入）。 */
  const stubNode = (data: Partial<BpmnNodeData>, size = { width: 120, height: 64 }): never =>
    ({
      size: () => size,
      getSize: () => size,
      prop: (key: string) => (key === 'size' ? { ...size } : undefined),
      getData: () => ({ ...data }),
    }) as never;

  it('should_render_full_name_label_without_clipping_when_event_rendered', () => {
    // AC-2（delta-r1 钉死口径）：事件/网关名称标签完整显示于图形下方不裁剪——
    // 标签元素不含 text-overflow: ellipsis 拼截断，且 overflow visible 或标签区宽不受节点宽约束
    const NAME = '开始超长名称标签完整性观测文本';
    for (const type of ['StartEvent', 'EndEvent', 'XOR', 'AND', 'BoundaryEvent'] as BpmnElementType[]) {
      const { container, unmount } = render(<BpmnNodeView node={stubNode({ type, name: NAME })} />);
      const root = container.querySelector<HTMLElement>('[data-bpmn-node]');
      expect(root, `${type} 根节点应带 data-bpmn-node`).toBeTruthy();
      const label = [...(root as HTMLElement).querySelectorAll<HTMLElement>('span,div')]
        .find((el) => el.childElementCount === 0 && el.textContent === NAME);
      expect(label, `${type} 名称标签叶元素应存在`).toBeTruthy();
      const style = (label as HTMLElement).style;
      expect(style.textOverflow, `${type} 标签不得 text-overflow: ellipsis 拼截断`).not.toBe('ellipsis');
      const overflowVisible = style.overflow === '' || style.overflow === 'visible';
      const widthUnconstrained = style.width === '' || style.width === 'auto' || style.width === 'max-content';
      expect(
        overflowVisible || widthUnconstrained,
        `${type} 标签应 overflow visible 或宽度不受节点宽约束（实际 overflow=${style.overflow} width=${style.width}）`,
      ).toBe(true);
      unmount();
    }
  });
});

// ————— AC-8/AC-9：DesignerPage 高度口径 + 工具栏装配 + 权限分控 + 容器面 —————

describe('REQ20261124224523 设计器页面装配（AC-8/AC-9）', () => {
  it('should_size_root_to_available_height_when_designer_opened', async () => {
    // AC-8：根高度口径 100vh−184px（背景钉死分解：Header 64+TabBar 36+Content padding 84）
    const container = await renderDesigner();
    const root = [...container.querySelectorAll<HTMLElement>('div')]
      .find((el) => /calc\(100vh/.test(el.style.height));
    expect(root, 'DesignerPage 应渲染带高度口径的根容器').toBeTruthy();
    expect((root as HTMLElement).style.height.replace(/\s+/g, ''), '根高度应为 calc(100vh - 184px)').toBe('calc(100vh-184px)');
  });

  it('should_assemble_toolbar_zones_and_refresh_button_when_designer_opened', async () => {
    // AC-9：左区=流程名+状态 Tag（+脏标记），按钮组在后（左右分区）；刷新按钮=ReloadOutlined+
    // aria-label=toolbar.refresh 键文案且可点击不报错
    const container = await renderDesigner();
    const name = screen.getByText('请假审批');
    expect(name, '流程名区应渲染').toBeTruthy();
    const refresh = await screen.findByRole('button', { name: '重置画布' });
    expect(refresh.querySelector('.anticon-reload'), '刷新按钮应为 ReloadOutlined 图标').toBeTruthy();
    expect(() => fireEvent.click(refresh), '刷新按钮可点击不报错').not.toThrow();
    // 左右分区：流程名在刷新/保存/部署按钮之前（DOM 先后序）
    const firstButton = [...container.querySelectorAll('button')].at(-1) as HTMLElement;
    expect(Boolean(firstButton && (name.compareDocumentPosition(firstButton) & Node.DOCUMENT_POSITION_FOLLOWING)),
      '流程名（左区）应在操作按钮组之前').toBe(true);
  });

  it('should_hide_save_and_deploy_when_user_lacks_deploy_permission', async () => {
    // 权限分控（79 fail-closed 既有行为回归）：仅 list(78) → 保存/部署不可见，刷新不受分控
    await renderDesigner([LIST_PERM]);
    expect(screen.queryAllByRole('button', { name: /保\s*存/ }), '无 deploy(79) 不应出现保存').toHaveLength(0);
    expect(screen.queryAllByRole('button', { name: /部\s*署/ }), '无 deploy(79) 不应出现部署').toHaveLength(0);
    expect(screen.getByRole('button', { name: '重置画布' }), '刷新按钮不受 deploy 分控').toBeTruthy();
  });

  it('should_show_save_and_deploy_when_user_has_deploy_permission', async () => {
    await renderDesigner();
    expect(screen.getAllByRole('button', { name: /保\s*存/ }).length, '保存草稿=deploy 分控面').toBeGreaterThanOrEqual(1);
    expect(screen.getAllByRole('button', { name: /部\s*署/ }).length, '部署=deploy 分控面').toBeGreaterThanOrEqual(1);
  });

  it('should_render_designer_containers_and_palette_items_when_designer_opened', async () => {
    // 钉死事实 1：容器 testid 全集 + Palette 白名单 8 条目
    await renderDesigner();
    for (const testId of ['bpmn-canvas', 'bpmn-minimap', 'bpmn-palette', 'validation-panel', 'property-panel']) {
      expect(screen.getByTestId(testId), `容器 testid=${testId} 应渲染`).toBeTruthy();
    }
    for (const type of ['StartEvent', 'EndEvent', 'XOR', 'AND', 'UserTask', 'ServiceTask', 'EmbeddedSubProcess', 'BoundaryEvent'] as BpmnElementType[]) {
      expect(screen.getByTestId(`palette-item-${type}`), `palette-item-${type} 应渲染`).toBeTruthy();
    }
    // 空闲态校验面板=紧凑状态条（AC-7 页面集成面）
    expect(screen.getByTestId('validation-panel').querySelector('.ant-empty')).toBeNull();
  });

  it('should_bind_auto_resize_sensor_to_dedicated_canvas_wrapper_when_designer_opened', async () => {
    // delta-r2 修复2：autoResize 传感器目标=仅含画布的专用包裹层（非画布容器自身/布尔 true）；
    // sensor 绑容器自身时 X6 transform.resize 写容器内联宽高钉死 offsetWidth 读数→结构性死锁
    await renderDesigner();
    const options = x6.state.graphCtor.at(-1);
    const autoResize = options?.autoResize as HTMLElement | undefined;
    expect(autoResize, 'Graph 构造应装配 autoResize 传感器目标').toBeTruthy();
    expect(typeof autoResize, '传感器目标应为元素（非布尔 true）').not.toBe('boolean');
    expect(autoResize?.querySelector('[data-testid="bpmn-canvas"]'), '传感器目标应包含画布容器').toBeTruthy();
    expect(autoResize?.querySelector('[data-testid="validation-panel"]'), '传感器目标不得包含校验条（防反馈环）').toBeNull();
  });

  it('should_keep_validation_panel_sibling_of_canvas_wrapper_when_designer_opened', async () => {
    // delta-r2 修复2防回归（TL 钉死）：校验条必须是画布包裹层的兄弟节点（不得置于包裹层内）——
    // 校验条在流内占高，若在包裹层内会参与传感器读数闭环→resize 反馈循环增长
    await renderDesigner();
    const canvas = screen.getByTestId('bpmn-canvas');
    const wrap = canvas.parentElement as HTMLElement;
    expect(wrap.getAttribute('data-testid'), '画布容器应被专用包裹层包裹').toBe('bpmn-canvas-wrap');
    expect(wrap.style.position, '包裹层应 position:relative（画布容器 absolute 脱流）').toBe('relative');
    expect(wrap.style.flex, '包裹层应 flex:1（占满画布列剩余空间；cssstyle 展开为 flex-grow 前置）').toMatch(/^1(\s|$)/);
    expect(wrap.style.minHeight, '包裹层应 minHeight:0（flex 列内可收缩）').toMatch(/^0(px)?$/);
    const panel = screen.getByTestId('validation-panel');
    expect(wrap.contains(panel), '校验条不得在包裹层内（resize 反馈环）').toBe(false);
    expect(panel.parentElement, '校验条应为包裹层兄弟节点（同列）').toBe(wrap.parentElement);
  });
});

// i18n 触达（避免 tree-shake 语义盲区；文案锚点=zh-CN workflow 资源包）
void resolveCanvasPalette;
void importModel;
