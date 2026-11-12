// 原型演示数据层 —— 仅供 prototypes 原型页消费（web/mobile 共用）。
// 约束：全部为确定性生成（同输入同输出，可测试）；不依赖任何端；不含真实凭证（密钥仅脱敏形态）。
// 生命周期：原型成熟化时，把对应函数迁入正式业务域，其余随原型一起删除。见 web/src/prototypes/README.md。
import type { AppState, Env, Platform, Release, Status, Tone } from '../types';

// ---------- 确定性随机 ----------
export type Rng = () => number;

export function hashSeed(input: string): number {
  let h = 2166136261;
  for (let i = 0; i < input.length; i += 1) {
    h ^= input.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
}

/** mulberry32：小而稳的可复现随机源，原型数据因此可测试、两端渲染一致。 */
export function createRng(seed: number | string): Rng {
  let a = typeof seed === 'number' ? seed >>> 0 : hashSeed(seed);
  return () => {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const pick = <T,>(rng: Rng, list: readonly T[]): T => list[Math.floor(rng() * list.length) % list.length];

const dayKey = (ms: number): string => {
  const d = new Date(ms);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
};

// ---------- A1 审批 ----------
export type ApprovalDecision = 'approved' | 'rejected' | 'pending';
export interface ApprovalStage {
  key: 'tech' | 'qa' | 'ops';
  label: string;
  approver: string;
  role: string;
  decision: ApprovalDecision;
  comment: string;
  at: string | null;
}

const APPROVAL_COMMENTS = [
  '灰度比例与回滚预案已确认，同意进入下一阶段。',
  '风险项已核对：缓存刷新策略与发布说明一致。',
  '回归范围已覆盖支付主链路，请保持窗口期监控。',
  '本阶段数据看板无异常波动，可以继续推进。',
  '历史版本快照已归档，满足回滚条件。',
];

/** 按发布单状态推导确定性审批链（tech → qa → ops）。 */
export function approvalFlow(release: Pick<Release, 'id' | 'status' | 'env' | 'updatedAt' | 'createdAt'>, now: number): ApprovalStage[] {
  const rng = createRng(`approval:${release.id}`);
  const roster: Array<Pick<ApprovalStage, 'key' | 'label' | 'approver' | 'role'>> = [
    { key: 'tech', label: '技术评审', approver: '周衍', role: '架构师' },
    { key: 'qa', label: '质量门禁', approver: '何青', role: 'QA 负责人' },
    { key: 'ops', label: '发布核准', approver: '陈屿', role: '值班 SRE' },
  ];
  const passed = release.status === 'approved' || release.status === 'published' ? 3 : release.status === 'testing' ? 1 : 0;
  const rejected = release.status === 'failed';
  const cancelled = release.status === 'cancelled';
  return roster.map((stage, i) => {
    let decision: ApprovalDecision = 'pending';
    if (cancelled) decision = 'pending';
    else if (rejected && i === 1) decision = 'rejected';
    else if (i < passed) decision = 'approved';
    const base = new Date(release.createdAt).getTime() + (i + 1) * 3600_000 * 3;
    const at = decision === 'pending' ? null : new Date(Math.min(base, now)).toISOString();
    return {
      ...stage,
      decision,
      comment:
        decision === 'pending'
          ? '等待审批'
          : decision === 'rejected'
            ? '快照回归不一致：小屏手势与系统返回冲突，需补充安全区适配后再提交。'
            : pick(rng, APPROVAL_COMMENTS),
      at,
    };
  });
}

// ---------- A2 构建日志 ----------
export type LogLevel = 'cmd' | 'info' | 'warn' | 'error';
export interface LogLine {
  offsetSec: number;
  level: LogLevel;
  stage: 'install' | 'build' | 'test' | 'package' | 'deploy';
  text: string;
}

/** 确定性构建日志：失败单在 test 阶段注入错误并中止；普通单完整走完。 */
export function buildLog(release: Pick<Release, 'id' | 'status' | 'version' | 'platform'>, seedExtra = ''): LogLine[] {
  const rng = createRng(`log:${release.id}:${seedExtra}`);
  const lines: LogLine[] = [];
  let t = 0;
  const push = (level: LogLevel, stage: LogLine['stage'], text: string) => {
    t += 2 + Math.floor(rng() * 9);
    lines.push({ offsetSec: t, level, stage, text });
  };
  push('cmd', 'install', `pnpm install --frozen-lockfile`);
  push('info', 'install', `resolved ${900 + Math.floor(rng() * 300)} packages in ${(18 + rng() * 20).toFixed(1)}s`);
  push('cmd', 'build', `pnpm --filter @acme/${release.platform === 'desktop' ? 'web' : release.platform} build`);
  push('info', 'build', `tsc --noEmit 通过（0 errors）`);
  push('info', 'build', `vite build → dist/（${(520 + rng() * 90).toFixed(0)} modules）`);
  push('cmd', 'test', `vitest run --coverage`);
  if (release.status === 'failed') {
    push('warn', 'test', '2 snapshot tests failed');
    push('error', 'test', `FAIL tests/ui/safe-area.test.ts — gesture conflict on small screens`);
    push('error', 'test', `process exited with code 1，流水线中止`);
    return lines;
  }
  push('info', 'test', `${140 + Math.floor(rng() * 60)} tests passed`);
  push('cmd', 'package', `artifact ${release.id}-${release.version}.zip 打包完成`);
  push('info', 'package', `checksum sha256:${rng().toString(16).slice(2, 10)}…（演示数据）`);
  if (release.status === 'published') {
    push('cmd', 'deploy', `publish → ${release.version} 已登记产物`);
    push('info', 'deploy', 'release note 已同步至动态流');
  }
  return lines;
}

// ---------- C1 报表 ----------
export interface TrendPoint {
  date: string;
  created: number;
  published: number;
}
export function publishTrend(state: Pick<AppState, 'releases'>, days: number, now: number): TrendPoint[] {
  const points: TrendPoint[] = [];
  for (let i = days - 1; i >= 0; i -= 1) {
    const dayMs = now - i * 86400_000;
    const key = dayKey(dayMs);
    const created = state.releases.filter((r) => dayKey(new Date(r.createdAt).getTime()) === key).length;
    const published = state.releases.filter(
      (r) => r.status === 'published' && dayKey(new Date(r.updatedAt).getTime()) === key,
    ).length;
    points.push({ date: key, created, published });
  }
  return points;
}

export interface NamedCount {
  name: string;
  count: number;
  tone: Tone;
}
export function failureReasons(state: Pick<AppState, 'releases'>): NamedCount[] {
  const notes = state.releases.filter((r) => r.status === 'failed');
  const buckets: Array<{ name: string; tone: Tone; match: (n: string) => boolean }> = [
    { name: '快照/视觉回归', tone: 'danger', match: (n) => /快照|视觉|手势/.test(n) },
    { name: '依赖或构建', tone: 'warning', match: (n) => /依赖|构建|安装/.test(n) },
    { name: '环境配置', tone: 'violet', match: (n) => /环境|配置|开关/.test(n) },
  ];
  const reason: NamedCount[] = buckets.map((b) => ({
    name: b.name,
    tone: b.tone,
    count: notes.filter((r) => b.match(r.notes)).length,
  }));
  const matched = reason.reduce((n, x) => n + x.count, 0);
  reason.push({ name: '其他', tone: 'muted', count: Math.max(0, notes.length - matched) });
  return reason.filter((x) => x.count > 0);
}

/** 阶段平均耗时（小时）：由种子时间线近似推导，仅作演示口径。 */
export function cycleTimeStats(state: Pick<AppState, 'releases'>): NamedCount[] {
  const done = state.releases.filter((r) => r.status === 'published');
  if (!done.length) return [];
  const span = (a: string, b: string) => Math.max(0, (new Date(b).getTime() - new Date(a).getTime()) / 3600_000);
  const avg = (pickFn: (r: Release) => number) =>
    done.reduce((sum, r) => sum + pickFn(r), 0) / done.length;
  return [
    { name: '创建 → 构建完成', count: Number(avg((r) => span(r.createdAt, r.timeline[1]?.at ?? r.updatedAt)).toFixed(1)), tone: 'info' },
    { name: '测试通过 → 审批', count: Number(avg((r) => span(r.timeline[1]?.at ?? r.createdAt, r.timeline[3]?.at ?? r.updatedAt)).toFixed(1)), tone: 'violet' },
    { name: '审批 → 发布', count: Number(avg((r) => span(r.timeline[3]?.at ?? r.createdAt, r.updatedAt)).toFixed(1)), tone: 'success' },
  ];
}

// ---------- A4 发布日历 ----------
export type DayWindow = 'morning' | 'afternoon' | 'night';
export interface ScheduleItem {
  releaseId: string;
  title: string;
  date: string;
  env: Env;
  window: DayWindow;
  status: Status;
}
const WINDOWS: DayWindow[] = ['morning', 'afternoon', 'night'];
const SCHEDULE_STATUSES: Status[] = ['building', 'testing', 'approved', 'draft'];

/** 未来 days 天排期：可运行中的单优先靠前，同日同环境制造可演示的冲突样本。 */
export function upcomingSchedule(state: Pick<AppState, 'releases'>, now: number, days = 14): ScheduleItem[] {
  const candidates = state.releases.filter((r) => SCHEDULE_STATUSES.includes(r.status));
  const rng = createRng(`schedule:${now}`);
  return candidates.slice(0, 8).map((r, i) => ({
    releaseId: r.id,
    title: r.title,
    date: dayKey(now + Math.max(1, ((i + Math.floor(rng() * 2)) % days) + 1) * 86400_000),
    env: r.env,
    window: WINDOWS[i % WINDOWS.length],
    status: r.status,
  }));
}

/** 同一天同一环境视为冲突（演示用简化口径）。 */
export function findConflicts(items: ScheduleItem[]): ScheduleItem[][] {
  const groups = new Map<string, ScheduleItem[]>();
  for (const item of items) {
    const key = `${item.date}#${item.env}`;
    groups.set(key, [...(groups.get(key) ?? []), item]);
  }
  return [...groups.values()].filter((g) => g.length > 1);
}

export const WINDOW_LABEL: Record<DayWindow, string> = { morning: '上午窗口', afternoon: '下午窗口', night: '夜间窗口' };

// ---------- A5 回滚 ----------
export interface RollbackOption {
  version: string;
  releasedAt: string;
  env: Env;
  risk: 'low' | 'medium' | 'high';
  note: string;
}
export const RISK_LABEL: Record<RollbackOption['risk'], string> = { low: '低风险', medium: '中风险', high: '高风险' };

export function rollbackOptions(state: Pick<AppState, 'releases'>, target: Release, now: number): RollbackOption[] {
  const rng = createRng(`rollback:${target.id}:${now}`);
  const history = state.releases
    .filter((r) => r.appId === target.appId && r.env === target.env && r.status === 'published' && r.version !== target.version)
    .slice(0, 3);
  const risks: RollbackOption['risk'][] = ['low', 'medium', 'high'];
  return history.map((r, i) => ({
    version: r.version,
    releasedAt: r.updatedAt,
    env: r.env,
    risk: risks[i % risks.length],
    note: pick(rng, [
      '数据库 schema 兼容，可直接切换。',
      '包含缓存结构变更，需先预热再切流。',
      '涉及配置中心键位，需值班双人复核。',
    ]),
  }));
}

// ---------- A6 编排 ----------
export interface OrchestrationNode {
  releaseId: string;
  title: string;
  appId: string;
  dependsOn: string[];
  status: Status;
}

/** 按「端依赖链 mobile → web → desktop」构造编排图（演示口径：桌面依赖 Web 产物）。 */
export function orchestrationGraph(state: Pick<AppState, 'releases' | 'apps'>): OrchestrationNode[] {
  const active = state.releases.filter((r) => ['draft', 'building', 'testing', 'approved'].includes(r.status));
  return active.map((r) => {
    const deps =
      r.platform === 'desktop'
        ? active.filter((x) => x.platform === 'web').map((x) => x.id)
        : r.platform === 'web'
          ? active.filter((x) => x.platform === 'mobile').map((x) => x.id)
          : [];
    return { releaseId: r.id, title: r.title, appId: r.appId, dependsOn: deps, status: r.status };
  });
}

/** Kahn 拓扑排序：返回安全执行顺序与成环节点（演示批量编排的可行性校验）。 */
export function topoSort(nodes: OrchestrationNode[]): { order: string[]; cycles: string[] } {
  const indegree = new Map(nodes.map((n) => [n.releaseId, 0]));
  const edges = new Map<string, string[]>();
  for (const n of nodes) {
    for (const dep of n.dependsOn) {
      if (!indegree.has(dep)) continue;
      indegree.set(n.releaseId, (indegree.get(n.releaseId) ?? 0) + 1);
      edges.set(dep, [...(edges.get(dep) ?? []), n.releaseId]);
    }
  }
  const ready = nodes.filter((n) => (indegree.get(n.releaseId) ?? 0) === 0).map((n) => n.releaseId);
  const order: string[] = [];
  while (ready.length) {
    const id = ready.shift()!;
    order.push(id);
    for (const next of edges.get(id) ?? []) {
      const left = (indegree.get(next) ?? 0) - 1;
      indegree.set(next, left);
      if (left === 0) ready.push(next);
    }
  }
  return { order, cycles: nodes.map((n) => n.releaseId).filter((id) => !order.includes(id)) };
}

// ---------- D1 成员与角色 ----------
export interface Member {
  id: string;
  name: string;
  role: 'owner' | 'dev' | 'qa' | 'ops' | 'viewer';
  email: string;
  apps: string[];
  tone: Tone;
}
export const ROLE_LABEL: Record<Member['role'], string> = {
  owner: '负责人',
  dev: '研发',
  qa: '测试',
  ops: '运维',
  viewer: '只读',
};
export const ROLE_TONE: Record<Member['role'], Tone> = {
  owner: 'success',
  dev: 'info',
  qa: 'violet',
  ops: 'warning',
  viewer: 'muted',
};
export const MEMBERS: Member[] = [
  { id: 'u-lin', name: '林晚', role: 'dev', email: 'lin.wan@acme.demo', apps: ['web'], tone: 'info' },
  { id: 'u-chen', name: '陈屿', role: 'ops', email: 'chen.yu@acme.demo', apps: ['desktop'], tone: 'warning' },
  { id: 'u-su', name: '苏然', role: 'dev', email: 'su.ran@acme.demo', apps: ['mobile'], tone: 'info' },
  { id: 'u-zhou', name: '周衍', role: 'qa', email: 'zhou.yan@acme.demo', apps: ['web', 'mobile'], tone: 'violet' },
  { id: 'u-he', name: '何青', role: 'owner', email: 'he.qing@acme.demo', apps: ['web', 'desktop', 'mobile'], tone: 'success' },
  { id: 'u-tang', name: '唐简', role: 'viewer', email: 'tang.jian@acme.demo', apps: [], tone: 'muted' },
];
/** 角色 × 权限矩阵（演示口径）。 */
export const PERMISSION_MATRIX: Array<{ action: string; roles: Member['role'][] }> = [
  { action: '创建/编辑发布单', roles: ['owner', 'dev', 'ops'] },
  { action: '推进构建与测试', roles: ['owner', 'dev'] },
  { action: '审批发布', roles: ['owner', 'qa', 'ops'] },
  { action: '执行发布/回滚', roles: ['owner', 'ops'] },
  { action: '编辑审批流配置', roles: ['owner'] },
  { action: '导出审计报表', roles: ['owner', 'ops'] },
];

// ---------- D2 通知 ----------
export interface Notice {
  id: string;
  at: string;
  category: 'approval' | 'build' | 'mention' | 'system';
  title: string;
  body: string;
  tone: Tone;
  read: boolean;
}
export const CATEGORY_LABEL: Record<Notice['category'], string> = {
  approval: '审批',
  build: '构建',
  mention: '提及',
  system: '系统',
};

export function notices(now: number, count = 12): Notice[] {
  const samples: Array<Omit<Notice, 'id' | 'at' | 'read'>> = [
    { category: 'approval', title: 'REL-2407 待你核准', body: '周衍请求发布核准：深色主题对比度修复 → prod', tone: 'warning' },
    { category: 'build', title: 'REL-2411 构建完成', body: '桌面端自动更新通道已进入测试阶段', tone: 'info' },
    { category: 'mention', title: '苏然 在 REL-2410 提到了你', body: '“离线缓存的迁移脚本需要林晚复核一下”', tone: 'violet' },
    { category: 'system', title: 'prod 发布窗口提醒', body: '每晚 22:00 后 prod 冻结，请避开夜间窗口', tone: 'muted' },
    { category: 'approval', title: 'REL-2406 驳回通知', body: '质量门禁未通过，原因见审批意见', tone: 'danger' },
    { category: 'build', title: '构建队列排队 2', body: '并发上限 3，预计等待 4 分钟', tone: 'info' },
  ];
  return samples.slice(0, count).map((s, i) => ({
    ...s,
    id: `notice-${i + 1}`,
    at: new Date(now - (i + 1) * 1800_000).toISOString(),
    read: i > 3,
  }));
}

// ---------- D3 评论 ----------
export interface ProtoComment {
  id: string;
  author: string;
  at: string;
  text: string;
  replies: ProtoComment[];
}
export function threads(state: Pick<AppState, 'releases'>, now: number): Array<{ releaseId: string; items: ProtoComment[] }> {
  const targets = state.releases.slice(0, 3);
  const rng = createRng(`threads:${now}`);
  return targets.map((r, i) => ({
    releaseId: r.id,
    items: [
      {
        id: `c-${i}-1`,
        author: '周衍',
        at: new Date(now - (i + 2) * 3600_000).toISOString(),
        text: pick(rng, [
          '灰度范围建议先收敛到 5% 用户，观察一天再放量。',
          '产物体积比上一版涨了 2.1MB，看看资源压缩。',
          '回滚预案已挂到发布单，请值班同学知悉。',
        ]),
        replies: [
          {
            id: `c-${i}-2`,
            author: r.owner,
            at: new Date(now - (i + 1) * 1800_000).toISOString(),
            text: '收到，今晚窗口前会同步最新进展。',
            replies: [],
          },
        ],
      },
    ],
  }));
}

// ---------- C3 审计 ----------
export interface AuditEntry {
  id: string;
  at: string;
  actor: string;
  action: string;
  target: string;
  detail: string;
  tone: Tone;
}
export function auditTrail(state: Pick<AppState, 'releases'>, now: number, count = 24): AuditEntry[] {
  const rng = createRng(`audit:${now}`);
  const actors = MEMBERS.map((m) => m.name);
  const actions: Array<{ action: string; tone: Tone; detail: string }> = [
    { action: '推进状态', tone: 'info', detail: '草稿 → 构建中' },
    { action: '审批通过', tone: 'success', detail: '质量门禁签核' },
    { action: '发布', tone: 'success', detail: '产物登记完成' },
    { action: '驳回', tone: 'danger', detail: '快照回归不一致' },
    { action: '编辑发布单', tone: 'info', detail: '修改负责人与备注' },
    { action: '导出数据', tone: 'muted', detail: '导出 JSON 备份' },
  ];
  return Array.from({ length: count }, (_, i) => {
    const r = state.releases[i % state.releases.length];
    const a = actions[Math.floor(rng() * actions.length) % actions.length];
    return {
      id: `audit-${i + 1}`,
      at: new Date(now - (i + 1) * 2700_000).toISOString(),
      actor: pick(rng, actors),
      action: a.action,
      target: r.id,
      detail: a.detail,
      tone: a.tone,
    };
  });
}

// ---------- E1 应用详情 ----------
export interface HealthPoint {
  at: string;
  uptime: number;
  latencyMs: number;
  errorRate: number;
}
export function healthSeries(appId: string, now: number, points = 24): HealthPoint[] {
  const rng = createRng(`health:${appId}`);
  const warning = appId === 'mobile';
  return Array.from({ length: points }, (_, i) => ({
    at: new Date(now - (points - 1 - i) * 3600_000).toISOString(),
    uptime: Number((warning ? 99.1 + rng() * 0.6 : 99.7 + rng() * 0.3).toFixed(2)),
    latencyMs: Math.round((warning ? 140 : 80) + rng() * 60),
    errorRate: Number((warning ? 0.4 : 0.05 + rng() * 0.25).toFixed(2)),
  }));
}

export interface EnvVar {
  key: string;
  value: string;
  scope: Env;
  secret: boolean;
}
/** 预置演示配置（secret 值一律只存脱敏形态）。 */
export const ENV_VARS: EnvVar[] = [
  { key: 'API_BASE_URL', value: 'https://api.acme.demo', scope: 'dev', secret: false },
  { key: 'FEATURE_GRAY_RELEASE', value: 'true', scope: 'test', secret: false },
  { key: 'PROD_DEPLOY_TOKEN', value: '••••••••••••3f2a（脱敏）', scope: 'prod', secret: true },
  { key: 'SENTRY_DSN', value: '••••••••9c11（脱敏）', scope: 'prod', secret: true },
];

export function maskSecret(value: string): string {
  if (value.length <= 4) return '•'.repeat(value.length);
  return `${'•'.repeat(Math.min(12, value.length - 4))}${value.slice(-4)}`;
}

// ---------- B1/B2 流程配置 ----------
export interface FlowRule {
  id: string;
  env: Env;
  minApprovers: number;
  approvers: string[];
  autoOnPass: boolean;
}
export const FLOW_RULES: FlowRule[] = [
  { id: 'rule-dev', env: 'dev', minApprovers: 0, approvers: [], autoOnPass: true },
  { id: 'rule-test', env: 'test', minApprovers: 1, approvers: ['何青'], autoOnPass: true },
  { id: 'rule-prod', env: 'prod', minApprovers: 2, approvers: ['何青', '陈屿'], autoOnPass: false },
];

export interface TemplateStage {
  key: string;
  label: string;
  kind: 'auto' | 'manual';
  estimateMin: number;
}
export interface PipelineTemplate {
  id: string;
  name: string;
  platform: Platform;
  stages: TemplateStage[];
}
export const TEMPLATES: PipelineTemplate[] = [
  {
    id: 'tpl-web',
    name: 'Web 标准流水线',
    platform: 'web',
    stages: [
      { key: 'install', label: '安装依赖', kind: 'auto', estimateMin: 2 },
      { key: 'build', label: '类型检查 + 构建', kind: 'auto', estimateMin: 4 },
      { key: 'test', label: '单测 + 快照回归', kind: 'auto', estimateMin: 6 },
      { key: 'approve', label: '质量门禁审批', kind: 'manual', estimateMin: 0 },
      { key: 'publish', label: '登记产物并发布', kind: 'auto', estimateMin: 1 },
    ],
  },
  {
    id: 'tpl-desktop',
    name: '桌面端发布流水线',
    platform: 'desktop',
    stages: [
      { key: 'wait-web', label: '等待 Web 产物', kind: 'auto', estimateMin: 5 },
      { key: 'package', label: '打包安装器', kind: 'auto', estimateMin: 8 },
      { key: 'sign', label: '签名校验', kind: 'auto', estimateMin: 2 },
      { key: 'approve', label: 'SRE 核准', kind: 'manual', estimateMin: 0 },
      { key: 'publish', label: '发布自动更新通道', kind: 'auto', estimateMin: 3 },
    ],
  },
  {
    id: 'tpl-mobile',
    name: '移动端灰度流水线',
    platform: 'mobile',
    stages: [
      { key: 'build', label: 'Hermes 打包', kind: 'auto', estimateMin: 6 },
      { key: 'test', label: '设备矩阵冒烟', kind: 'auto', estimateMin: 10 },
      { key: 'gray', label: '灰度 5% → 50%', kind: 'manual', estimateMin: 0 },
      { key: 'publish', label: '全量发布', kind: 'auto', estimateMin: 2 },
    ],
  },
];

// ---------- B3 晋升看板 ----------
export const PROMOTION_COLUMNS: Array<{ env: Env; label: string }> = [
  { env: 'dev', label: '开发' },
  { env: 'test', label: '测试' },
  { env: 'prod', label: '生产' },
];
