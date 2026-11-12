// 可视化看板原型数据层 —— 与 data.ts 同纪律：确定性、纯函数、不含真实凭证。
// Web 端用内建 SVG/CSS 渲染；Mobile 端用纯 View 近似（多边形雷达在 RN 以横向条呈现，与
// 「渐变以纯色近似」同一 idiomatic 取舍，见 UI-SPEC §3）。不引入任何未登记图表依赖。
import type { AppState, Release, Status, Tone } from '../types';
import { createRng } from './data';

const dayKey = (ms: number): string => {
  const d = new Date(ms);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
};

const clamp = (n: number, min = 0, max = 100) => Math.min(max, Math.max(min, n));

// ---------- V2 发布热力图 ----------
export interface HeatCell {
  date: string;
  created: number;
  published: number;
  /** 0-4 强度档：0 无活动；活动分 = created + published×2 */
  level: 0 | 1 | 2 | 3 | 4;
}

export function publishHeatmap(state: Pick<AppState, 'releases'>, weeks: number, now: number): HeatCell[] {
  const cells: HeatCell[] = [];
  const totalDays = weeks * 7;
  for (let i = totalDays - 1; i >= 0; i -= 1) {
    const dayMs = now - i * 86400_000;
    const key = dayKey(dayMs);
    const created = state.releases.filter((r) => dayKey(new Date(r.createdAt).getTime()) === key).length;
    const published = state.releases.filter(
      (r) => r.status === 'published' && dayKey(new Date(r.updatedAt).getTime()) === key,
    ).length;
    const score = created + published * 2;
    const level: HeatCell['level'] = score === 0 ? 0 : score < 3 ? 1 : score < 6 ? 2 : score < 10 ? 3 : 4;
    cells.push({ date: key, created, published, level });
  }
  return cells;
}

// ---------- V3 发布漏斗 ----------
export interface FunnelStage {
  key: Status;
  label: string;
  count: number;
  tone: Tone;
}

/** 在途漏斗：草稿 → 构建 → 测试 → 审批 → 已发布（当前各阶段数量）。 */
export function releaseFunnel(state: Pick<AppState, 'releases'>): FunnelStage[] {
  const stages: Array<[Status, string, Tone]> = [
    ['draft', '草稿', 'muted'],
    ['building', '构建中', 'info'],
    ['testing', '测试中', 'violet'],
    ['approved', '待发布', 'warning'],
    ['published', '已发布', 'success'],
  ];
  return stages.map(([key, label, tone]) => ({
    key,
    label,
    tone,
    count: state.releases.filter((r) => r.status === key).length,
  }));
}

// ---------- V4 应用雷达 ----------
export interface RadarAxis {
  key: string;
  label: string;
  score: number;
}
export interface RadarScore {
  appId: string;
  appName: string;
  axes: RadarAxis[];
}

/** 五维评分（0-100）：吞吐 / 成功率 / 交付速度 / 稳定性 / 质量门禁，全部由正式 store 推导。 */
export function appRadar(state: Pick<AppState, 'releases' | 'apps'>, now: number): RadarScore[] {
  const rng = createRng(`radar:${now}`);
  const maxCount = Math.max(1, ...state.apps.map((a) => state.releases.filter((r) => r.appId === a.id).length));
  return state.apps.map((app) => {
    const rels = state.releases.filter((r) => r.appId === app.id);
    const published = rels.filter((r) => r.status === 'published');
    const failed = rels.filter((r) => r.status === 'failed').length;
    const throughput = clamp((rels.length / maxCount) * 100);
    const success = rels.length ? clamp(((published.length + rels.filter((r) => r.status === 'approved').length) / rels.length) * 100) : 60;
    const avgHours = published.length
      ? published.reduce((n, r) => n + (new Date(r.updatedAt).getTime() - new Date(r.createdAt).getTime()) / 3600_000, 0) / published.length
      : 48;
    const speed = clamp(100 - (avgHours / 72) * 100);
    // 稳定性：演示环境无真实监控，用确定性基线 + 应用差异（web 基线高，mobile 略低）
    const stabilityBase = app.id === 'web' ? 96 : app.id === 'desktop' ? 93 : 88;
    const stability = clamp(stabilityBase + rng() * 3 - failed * 4);
    const quality = rels.length ? clamp(100 - (failed / rels.length) * 100) : 80;
    return {
      appId: app.id,
      appName: app.name,
      axes: [
        { key: 'throughput', label: '交付吞吐', score: Math.round(throughput) },
        { key: 'success', label: '成功率', score: Math.round(success) },
        { key: 'speed', label: '交付速度', score: Math.round(speed) },
        { key: 'stability', label: '稳定性', score: Math.round(stability) },
        { key: 'quality', label: '质量门禁', score: Math.round(quality) },
      ],
    };
  });
}

// ---------- V5 版本时间线（甘特） ----------
export interface GanttRow {
  releaseId: string;
  title: string;
  appId: string;
  start: string;
  end: string;
  status: Status;
  tone: Tone;
}
export interface GanttWindow {
  rows: GanttRow[];
  start: string;
  end: string;
  today: string;
}

/** 近 days 天窗口内的发布单横条；进行中的单延伸至今日。 */
export function releaseGantt(state: Pick<AppState, 'releases' | 'apps'>, now: number, days = 30, max = 8): GanttWindow {
  const windowStart = now - days * 86400_000;
  const active: Status[] = ['draft', 'building', 'testing', 'approved'];
  const candidates = state.releases
    .filter((r) => new Date(r.updatedAt).getTime() >= windowStart)
    .sort((a, b) => a.priority - b.priority)
    .slice(0, max);
  const toneOf = (s: Status): Tone =>
    s === 'published' ? 'success' : s === 'failed' ? 'danger' : s === 'approved' ? 'warning' : 'info';
  return {
    rows: candidates.map((r) => ({
      releaseId: r.id,
      title: r.title,
      appId: r.appId,
      start: dayKey(Math.max(new Date(r.createdAt).getTime(), windowStart)),
      end: dayKey(active.includes(r.status) ? now : new Date(r.updatedAt).getTime()),
      status: r.status,
      tone: toneOf(r.status),
    })),
    start: dayKey(windowStart),
    end: dayKey(now),
    today: dayKey(now),
  };
}

// ---------- V1 阶段管道链 ----------
export interface PipelineStageView {
  key: string;
  label: string;
  state: 'done' | 'active' | 'pending' | 'failed';
  note?: string;
}

const CHAIN: Status[] = ['draft', 'building', 'testing', 'approved', 'published'];

/** 单条发布单的阶段链视图：draft→building→testing→approved→published。 */
export function pipelineChain(release: Pick<Release, 'status' | 'progress'>): PipelineStageView[] {
  const idx = CHAIN.indexOf(release.status);
  const cancelled = release.status === 'cancelled';
  const failed = release.status === 'failed';
  return CHAIN.map((s, i) => {
    const labels: Record<Status, string> = {
      draft: '草稿', building: '构建', testing: '测试', approved: '审批', published: '发布',
      failed: '失败', cancelled: '取消',
    };
    let state: PipelineStageView['state'] = 'pending';
    if (failed && s === 'testing') state = 'failed';
    else if (i < idx) state = 'done';
    else if (i === idx) state = s === 'published' ? 'done' : 'active';
    let note: string | undefined;
    if (state === 'active' && s === 'building') note = `进度 ${release.progress}%`;
    if (cancelled && i <= idx) state = 'done';
    if (cancelled && i === idx + 1) note = '已取消';
    return { key: s, label: labels[s], state, note };
  });
}

// ---------- V6 节点状态墙 ----------
export type NodeStatus = 'healthy' | 'building' | 'idle' | 'error';
export interface BuildNode {
  id: string;
  name: string;
  status: NodeStatus;
  load: number;
  job?: string;
}

export const NODE_STATUS_META: Record<NodeStatus, { label: string; tone: Tone }> = {
  healthy: { label: '在线空闲', tone: 'success' },
  building: { label: '构建中', tone: 'info' },
  idle: { label: '低负载', tone: 'muted' },
  error: { label: '异常', tone: 'danger' },
};

/** 构建集群节点墙（确定性演示）：约 1/10 异常率，构建节点挂当前 building 发布单。 */
export function buildNodes(state: Pick<AppState, 'releases'>, now: number, count = 24): BuildNode[] {
  const rng = createRng(`nodes:${now}`);
  const building = state.releases.filter((r) => r.status === 'building');
  return Array.from({ length: count }, (_, i) => {
    const roll = rng();
    const status: NodeStatus = roll < 0.08 ? 'error' : roll < 0.15 ? 'idle' : i < building.length ? 'building' : roll < 0.7 ? 'healthy' : 'idle';
    const job = status === 'building' ? building[i % Math.max(1, building.length)]?.id : undefined;
    return {
      id: `node-${String(i + 1).padStart(2, '0')}`,
      name: `runner-${String.fromCharCode(97 + (i % 6))}${Math.floor(i / 6) + 1}`,
      status,
      load: Math.round(clamp(status === 'building' ? 60 + rng() * 35 : status === 'idle' ? rng() * 20 : 20 + rng() * 40)),
      job,
    };
  });
}
