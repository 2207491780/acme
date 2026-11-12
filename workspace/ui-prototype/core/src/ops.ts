// 发布工程 / 环境 / 稳定性 / 集成 原型数据层（增量四）—— 纪律同前：确定性、纯函数、零凭证。
// 从正式 store 推导（就绪度/制品库/diff/环境），演示性结构（拓扑/灰度/集成）用确定性种子。
import type { AppState, Env, Release, Tone } from '../types';
import { approvalFlow, createRng, ENV_VARS, rollbackOptions } from './data';

const clamp01 = (n: number) => Math.min(1, Math.max(0, n));

// ---------- R1 发布就绪度（Go/No-Go） ----------
export interface ReadinessItem {
  key: string;
  label: string;
  pass: boolean;
  note: string;
  blocking: boolean;
}
export interface ReadinessReport {
  releaseId: string;
  score: number;
  ready: boolean;
  items: ReadinessItem[];
}

/** 发布前 Go/No-Go 检查矩阵：构建/测试/审批/产物/窗口/回滚预案，全部由 store 与 A1 口径推导。 */
export function readinessReport(state: Pick<AppState, 'releases' | 'apps'>, releaseId: string, now: number): ReadinessReport | null {
  const release = state.releases.find((r) => r.id === releaseId);
  if (!release) return null;
  const hour = new Date(now).getHours();
  const flow = approvalFlow(release, now);
  const approvedCount = flow.filter((s) => s.decision === 'approved').length;
  const pastTesting = ['testing', 'approved', 'published'].includes(release.status);

  const items: ReadinessItem[] = [
    {
      key: 'build',
      label: '构建完成',
      pass: pastTesting,
      blocking: true,
      note: release.status === 'building' ? `构建进行中（${release.progress}%）` : pastTesting ? '构建产物已生成' : '尚未开始构建',
    },
    {
      key: 'test',
      label: '测试通过',
      pass: ['approved', 'published'].includes(release.status) && release.status !== 'failed',
      blocking: true,
      note: release.status === 'testing' ? '测试进行中' : release.status === 'failed' ? '测试失败，存在阻塞缺陷' : ['approved', 'published'].includes(release.status) ? '快照与用例全绿' : '未进入测试',
    },
    {
      key: 'approval',
      label: '审批齐备',
      pass: approvedCount >= 3 || (release.env !== 'prod' && approvedCount >= 1),
      blocking: release.env === 'prod',
      note: `${approvedCount}/3 级签署（${release.env === 'prod' ? 'prod 需全部签核' : '非 prod 至少 1 级'}）`,
    },
    {
      key: 'artifact',
      label: '产物就绪',
      pass: release.artifacts.length > 0,
      blocking: true,
      note: release.artifacts.length ? `${release.artifacts[0].name}（${release.artifacts[0].size}）` : '审批通过后生成产物',
    },
    {
      key: 'window',
      label: '发布窗口',
      pass: release.env !== 'prod' || (hour < 22 && hour >= 8),
      blocking: release.env === 'prod',
      note: release.env === 'prod' ? (hour < 22 && hour >= 8 ? '处于 prod 允许窗口（08:00-22:00）' : 'prod 冻结期（22:00-08:00）') : '非 prod 无窗口限制',
    },
    {
      key: 'rollback',
      label: '回滚预案',
      pass: rollbackOptions(state, release, now).length > 0 || release.status === 'draft',
      blocking: release.env === 'prod',
      note: rollbackOptions(state, release, now).length ? `${rollbackOptions(state, release, now).length} 个可回滚版本` : '该应用在此环境暂无历史版本（首次发布）',
    },
  ];
  const score = Math.round((items.filter((i) => i.pass).length / items.length) * 100);
  const blockingFail = items.some((i) => i.blocking && !i.pass);
  return { releaseId: release.id, score, ready: !blockingFail && score === 100, items };
}

// ---------- R3 制品库 ----------
export interface ArtifactRow {
  name: string;
  releaseId: string;
  releaseTitle: string;
  appId: string;
  version: string;
  size: string;
  env: Env;
  status: Release['status'];
  createdAt: string;
}

export function artifactInventory(state: Pick<AppState, 'releases' | 'apps'>): ArtifactRow[] {
  const rows: ArtifactRow[] = [];
  for (const r of state.releases) {
    for (const a of r.artifacts) {
      rows.push({
        name: a.name,
        releaseId: r.id,
        releaseTitle: r.title,
        appId: r.appId,
        version: r.version,
        size: a.size,
        env: r.env,
        status: r.status,
        createdAt: r.updatedAt,
      });
    }
  }
  return rows.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
}

// ---------- R4 版本对比 ----------
export interface DiffField {
  field: string;
  a: string;
  b: string;
  changed: boolean;
}
export interface ReleaseDiffResult {
  a: Release | null;
  b: Release | null;
  fields: DiffField[];
}

export function releaseDiff(state: Pick<AppState, 'releases'>, idA: string, idB: string): ReleaseDiffResult {
  const a = state.releases.find((r) => r.id === idA) ?? null;
  const b = state.releases.find((r) => r.id === idB) ?? null;
  const fields: DiffField[] = [];
  const push = (field: string, va: unknown, vb: unknown) =>
    fields.push({ field, a: String(va ?? '—'), b: String(vb ?? '—'), changed: String(va ?? '—') !== String(vb ?? '—') });
  if (a && b) {
    push('标题', a.title, b.title);
    push('版本', a.version, b.version);
    push('环境', a.env, b.env);
    push('平台', a.platform, b.platform);
    push('负责人', a.owner, b.owner);
    push('优先级', `P${a.priority}`, `P${b.priority}`);
    push('状态', a.status, b.status);
    push('产物数', a.artifacts.length, b.artifacts.length);
    push('时间线条目', a.timeline.length, b.timeline.length);
  }
  return { a, b, fields };
}

// ---------- H1 环境 ----------
export interface EnvSummary {
  env: Env;
  label: string;
  deployedVersion: string | null;
  publishes: number;
  lastPublishAt: string | null;
  freeze: string;
  varCount: number;
  inFlight: number;
  tone: Tone;
}

const ENV_META: Record<Env, { label: string; freeze: string; tone: Tone }> = {
  dev: { label: '开发', freeze: '无冻结', tone: 'muted' },
  test: { label: '测试', freeze: '23:00-07:00', tone: 'warning' },
  prod: { label: '生产', freeze: '22:00-08:00 + 双人审批', tone: 'danger' },
};

export function envOverview(state: Pick<AppState, 'releases'>): EnvSummary[] {
  return (['dev', 'test', 'prod'] as Env[]).map((env) => {
    const published = state.releases
      .filter((r) => r.env === env && r.status === 'published')
      .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
    const inFlight = state.releases.filter((r) => r.env === env && ['building', 'testing', 'approved'].includes(r.status)).length;
    return {
      env,
      label: ENV_META[env].label,
      tone: ENV_META[env].tone,
      deployedVersion: published[0]?.version ?? null,
      publishes: published.length,
      lastPublishAt: published[0]?.updatedAt ?? null,
      freeze: ENV_META[env].freeze,
      varCount: ENV_VARS.filter((v) => v.scope === env).length + (env === 'prod' ? 2 : 1),
      inFlight,
    };
  });
}

// ---------- H2 部署拓扑 ----------
export interface TopologyNode {
  id: string;
  label: string;
  kind: 'app' | 'channel';
  version: string | null;
  tone: Tone;
}
export interface TopologyEdge {
  from: string;
  to: string;
  label: string;
}

/** 部署拓扑（演示口径）：应用 → 分发渠道；桌面依赖 Web 产物。 */
export function deploymentTopology(state: Pick<AppState, 'releases' | 'apps'>): { nodes: TopologyNode[]; edges: TopologyEdge[] } {
  const nodes: TopologyNode[] = state.apps.map((a) => ({
    id: a.id,
    label: a.name,
    kind: 'app' as const,
    version: a.version,
    tone: a.id === 'web' ? 'info' : a.id === 'desktop' ? 'violet' : 'success',
  }));
  nodes.push(
    { id: 'ch-browser', label: '浏览器 / PWA', kind: 'channel', version: null, tone: 'muted' },
    { id: 'ch-updater', label: '自动更新通道', kind: 'channel', version: null, tone: 'muted' },
    { id: 'ch-store', label: '应用商店 / Expo Go', kind: 'channel', version: null, tone: 'muted' },
  );
  const edges: TopologyEdge[] = [
    { from: 'web', to: 'ch-browser', label: '静态产物' },
    { from: 'web', to: 'desktop', label: '构建产物注入' },
    { from: 'desktop', to: 'ch-updater', label: '安装包' },
    { from: 'mobile', to: 'ch-store', label: 'Hermes 包' },
  ];
  return { nodes, edges };
}

// ---------- S1 事故与事件流 ----------
export interface Incident {
  id: string;
  releaseId: string;
  title: string;
  appId: string;
  env: Env;
  openedAt: string;
  severity: 'P1' | 'P2' | 'P3';
  mttrHours: number | null;
  resolved: boolean;
  rollbackReady: boolean;
}

export function incidents(state: Pick<AppState, 'releases'>, now: number): { list: Incident[]; mttrAvgHours: number; open: number } {
  const failed = state.releases.filter((r) => r.status === 'failed');
  const rng = createRng(`incident:${now}`);
  const list: Incident[] = failed.map((r, i) => {
    const severity = r.env === 'prod' ? 'P1' : i % 2 === 0 ? 'P2' : 'P3';
    const resolved = rng() > 0.35;
    return {
      id: `INC-${String(i + 1).padStart(3, '0')}`,
      releaseId: r.id,
      title: r.title,
      appId: r.appId,
      env: r.env,
      openedAt: r.updatedAt,
      severity,
      mttrHours: resolved ? Number((4 + rng() * 28).toFixed(1)) : null,
      resolved,
      rollbackReady: rollbackOptions(state, r, now).length > 0,
    };
  });
  const resolvedHours = list.filter((x) => x.mttrHours !== null).map((x) => x.mttrHours!);
  return {
    list,
    mttrAvgHours: resolvedHours.length ? Number((resolvedHours.reduce((n, x) => n + x, 0) / resolvedHours.length).toFixed(1)) : 0,
    open: list.filter((x) => !x.resolved).length,
  };
}

// ---------- S2 灰度发布 ----------
export interface GrayStage {
  percent: number;
  state: 'done' | 'active' | 'pending';
  successRate: number;
  feedback: string;
}
export interface GrayPlan {
  id: string;
  appId: string;
  appName: string;
  version: string;
  paused: boolean;
  stages: GrayStage[];
}

const GRAY_FEEDBACK = [
  '崩溃率持平，用户反馈正常',
  'A/B 指标小幅正向，继续观察',
  '性能指标达标，可放量',
  '收到 3 例小屏反馈，暂缓放量',
];

/** 灰度计划（确定性演示）：取最近已发布单，构造 5%→50%→100% 三阶段放量。 */
export function grayPlans(state: Pick<AppState, 'releases' | 'apps'>, now: number, count = 2): GrayPlan[] {
  const published = state.releases.filter((r) => r.status === 'published').slice(0, count);
  return published.map((r, idx) => {
    const rng = createRng(`gray:${r.id}:${now}`);
    const paused = idx === 1 && rng() > 0.5;
    const rates = [99.2 + rng() * 0.7, 98.6 + rng() * 0.8, 0];
    return {
      id: `gray-${r.id}`,
      appId: r.appId,
      appName: state.apps.find((a) => a.id === r.appId)?.name ?? r.appId,
      version: r.version,
      paused,
      stages: [5, 50, 100].map((percent, i) => ({
        percent,
        state: i === 0 ? 'done' : i === 1 && !paused ? 'active' : i === 1 && paused ? 'active' : 'pending',
        successRate: i < 2 ? Number(rates[i].toFixed(2)) : 0,
        feedback: i === 1 && paused ? GRAY_FEEDBACK[3] : i < 2 ? GRAY_FEEDBACK[i % 3] : '等待放量',
      })),
    } satisfies GrayPlan;
  });
}

// ---------- G1 集成中心 ----------
export interface IntegrationCard {
  id: string;
  name: string;
  category: '通知' | '代码源' | '监控' | '制品';
  status: 'connected' | 'disabled' | 'error';
  endpoint: string;
  lastSync: string | null;
  note: string;
}

export const INTEGRATION_META: Record<IntegrationCard['status'], { label: string; tone: Tone }> = {
  connected: { label: '已连接', tone: 'success' },
  disabled: { label: '未启用', tone: 'muted' },
  error: { label: '异常', tone: 'danger' },
};

export function integrationCards(now: number): IntegrationCard[] {
  const last = (min: number) => new Date(now - min * 60000).toISOString();
  return [
    { id: 'git', name: '代码仓库（Git）', category: '代码源', status: 'connected', endpoint: 'https://git.example.com/acme（演示）', lastSync: last(12), note: '提交状态与发布单关联' },
    { id: 'registry', name: '制品库', category: '制品', status: 'connected', endpoint: 'acme://registry/snapshots（演示）', lastSync: last(35), note: '产物归档与保留策略' },
    { id: 'monitor', name: '监控告警', category: '监控', status: 'error', endpoint: 'https://monitor.acme.demo（脱敏）', lastSync: last(240), note: '最近同步失败 2 次，请检查凭证' },
    { id: 'webhook', name: 'IM 通知 Webhook', category: '通知', status: 'connected', endpoint: '••••••••/hook/（脱敏）', lastSync: last(3), note: '构建/审批/发布事件推送' },
    { id: 'mail', name: '邮件通知', category: '通知', status: 'disabled', endpoint: '—', lastSync: null, note: '未配置 SMTP，启用需登记凭证' },
  ];
}

// ---------- X1 全局搜索（聚合口径供页面实现参考） ----------
export interface SearchHit {
  kind: 'release' | 'app' | 'member';
  id: string;
  title: string;
  sub: string;
}

export function globalSearch(state: Pick<AppState, 'releases' | 'apps'>, members: Array<{ name: string; email: string }>, query: string, limit = 8): SearchHit[] {
  const q = query.trim().toLowerCase();
  if (!q) return [];
  const hits: SearchHit[] = [];
  for (const r of state.releases) {
    if (`${r.id}${r.title}${r.version}${r.owner}`.toLowerCase().includes(q)) {
      hits.push({ kind: 'release', id: r.id, title: `${r.id} · ${r.title}`, sub: `v${r.version} · ${r.owner} · ${r.status}` });
    }
  }
  for (const a of state.apps) {
    if (`${a.id}${a.name}${a.path}`.toLowerCase().includes(q)) {
      hits.push({ kind: 'app', id: a.id, title: a.name, sub: `应用 · ${a.path}` });
    }
  }
  for (const m of members) {
    if (`${m.name}${m.email}`.toLowerCase().includes(q)) {
      hits.push({ kind: 'member', id: m.name, title: m.name, sub: `成员 · ${m.email}` });
    }
  }
  return hits.slice(0, limit);
}

export const OPS_WINDOW_LABEL = 'prod 允许窗口 08:00-22:00（冻结 22:00-08:00）';
export const opsRatio = (part: number, total: number): number => (total === 0 ? 0 : Number(clamp01(part / total).toFixed(4)));
