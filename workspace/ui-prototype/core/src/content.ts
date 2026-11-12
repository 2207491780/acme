// 内容与治理原型数据层（增量六）—— 纪律同前：确定性、纯函数、零凭证。
// 公告/规范文档/功能开关/新手引导清单；备份快照为确定性演示。
import type { AppState, Tone } from '../types';
import { createRng } from './data';

// ---------- W1 公告与规范文档 ----------
export interface Announcement {
  id: string;
  title: string;
  body: string;
  author: string;
  at: string;
  pinned: boolean;
  tone: Tone;
}

export function announcements(now: number): Announcement[] {
  const list: Announcement[] = [
    {
      id: 'ann-1',
      title: 'prod 发布窗口纪律（即日起执行）',
      body:
        '**prod 冻结窗口：22:00-08:00**。窗口外的 prod 发布一律驳回。\n\n- 发布前必须完成 R1 就绪度检查（GO）\n- 高风险回滚需双人复核\n- 复盘记录在 3 个工作日内归档',
      author: '何青',
      at: new Date(now - 2 * 3600_000).toISOString(),
      pinned: true,
      tone: 'warning',
    },
    {
      id: 'ann-2',
      title: '灰度放量阈值调整：错误率 0.5% 自动暂停',
      body: 'S2 灰度计划新增自动暂停阈值：**当前批次错误率 > 0.5%** 即暂停放量并通知负责人，处理后可恢复。',
      author: '陈屿',
      at: new Date(now - 26 * 3600_000).toISOString(),
      pinned: false,
      tone: 'info',
    },
    {
      id: 'ann-3',
      title: '制品库保留策略变更公示',
      body: 'prod 产物保留 **90 天**、test **30 天**（R3 口径），超期制品将在每周日凌晨归档清理。',
      author: '苏然',
      at: new Date(now - 3 * 86400_000).toISOString(),
      pinned: false,
      tone: 'muted',
    },
  ];
  return list.sort((a, b) => Number(b.pinned) - Number(a.pinned) || b.at.localeCompare(a.at));
}

export interface DocCard {
  id: string;
  title: string;
  kind: '规范' | '指南' | '复盘';
  updatedAt: string;
  tone: Tone;
}

export function docCards(now: number): DocCard[] {
  return [
    { id: 'doc-release', title: '多端发布流程规范 v2', kind: '规范', updatedAt: new Date(now - 5 * 86400_000).toISOString(), tone: 'info' },
    { id: 'doc-rollback', title: '回滚操作手册（含双人复核）', kind: '指南', updatedAt: new Date(now - 12 * 86400_000).toISOString(), tone: 'warning' },
    { id: 'doc-gray', title: '灰度放量决策指南', kind: '指南', updatedAt: new Date(now - 20 * 86400_000).toISOString(), tone: 'violet' },
    { id: 'doc-review', title: 'REL-2406 手势冲突复盘', kind: '复盘', updatedAt: new Date(now - 30 * 86400_000).toISOString(), tone: 'danger' },
  ];
}

// ---------- W2 功能开关 ----------
export interface FeatureFlag {
  key: string;
  name: string;
  description: string;
  enabled: boolean;
  scope: 'all' | 'dev' | 'test' | 'prod';
  owner: string;
  impact: string;
}

export const FLAG_SCOPE_LABEL: Record<FeatureFlag['scope'], string> = {
  all: '全环境',
  dev: '开发',
  test: '测试',
  prod: '生产',
};

export const FEATURE_FLAGS: FeatureFlag[] = [
  {
    key: 'flag-auto-tick',
    name: '构建进度自动推进',
    description: '进行中的发布单按 TICK 自动推进构建进度（关闭后需手动推进）。',
    enabled: true,
    scope: 'all',
    owner: '陈屿',
    impact: '关闭后实时大盘（C2）将不再自动刷新进度',
  },
  {
    key: 'flag-gray-auto-pause',
    name: '灰度自动暂停阈值',
    description: '当前批次错误率 > 0.5% 时自动暂停放量（S2）。',
    enabled: true,
    scope: 'prod',
    owner: '陈屿',
    impact: '关闭后灰度异常需人工发现',
  },
  {
    key: 'flag-prod-double-approve',
    name: 'prod 双人审批',
    description: 'prod 发布需两级审批全部签核（B1/FLOW_RULES）。',
    enabled: true,
    scope: 'prod',
    owner: '何青',
    impact: '关闭违反 prod 发布纪律，不建议',
  },
  {
    key: 'flag-calendar-conflict',
    name: '排期冲突检测',
    description: '同日同环境多条发布时标红冲突（A4）。',
    enabled: false,
    scope: 'test',
    owner: '周衍',
    impact: '关闭后 A4 不再提示冲突，仅展示排期',
  },
];

// ---------- X3 新手引导清单 ----------
export interface OnboardingStep {
  key: string;
  label: string;
  desc: string;
  /** 跳转路由（/prototype/* 或正式页）。 */
  target: string;
  /** 自动判定函数的标识；无标识的步骤由用户手动勾选。 */
  auto?: 'has-published' | 'has-approved' | 'has-artifact';
}

export const ONBOARDING_STEPS: OnboardingStep[] = [
  { key: 'ob-create', label: '创建首个发布单', desc: '在发布单页新建一条（或用 R2 向导体验多步创建）。', target: '/prototype/create-wizard' },
  { key: 'ob-advance', label: '推进一次状态流转', desc: '草稿 → 构建中，观察 TICK 与管道图（V1）。', target: '/releases', auto: 'has-approved' },
  { key: 'ob-approve', label: '完成一次审批', desc: '在 A1 处理待办或查看签署记录。', target: '/prototype/approval', auto: 'has-approved' },
  { key: 'ob-artifact', label: '查看产物', desc: '审批通过后产物生成，在 A3/R3 查看与下载。', target: '/prototype/artifacts', auto: 'has-artifact' },
  { key: 'ob-publish', label: '完成一次发布', desc: '发布后进入动态流与报表统计。', target: '/prototype/reports', auto: 'has-published' },
  { key: 'ob-rollback', label: '演练回滚', desc: '在 A5 向导走一遍安全回滚（仅本地生效）。', target: '/prototype/rollback' },
];

export function onboardingAutoState(state: Pick<AppState, 'releases'>, step: OnboardingStep): boolean | null {
  if (step.auto === 'has-published') return state.releases.some((r) => r.status === 'published');
  if (step.auto === 'has-approved') return state.releases.some((r) => r.status === 'approved' || r.status === 'published');
  if (step.auto === 'has-artifact') return state.releases.some((r) => r.artifacts.length > 0);
  return null;
}

// ---------- W4 备份快照（确定性演示） ----------
export interface BackupSnapshot {
  id: string;
  createdAt: string;
  releases: number;
  apps: number;
  sizeKb: number;
  tone: Tone;
}

export function backupSnapshots(state: Pick<AppState, 'releases' | 'apps'>, now: number): BackupSnapshot[] {
  const rng = createRng(`backup:${now}`);
  return Array.from({ length: 3 }, (_, i) => ({
    id: `bak-${String(i + 1).padStart(3, '0')}`,
    createdAt: new Date(now - (i + 1) * 86400_000).toISOString(),
    releases: state.releases.length,
    apps: state.apps.length,
    sizeKb: Math.round((18 + rng() * 12) * 10) / 10,
    tone: i === 0 ? 'success' : 'muted',
  })) satisfies BackupSnapshot[];
}
