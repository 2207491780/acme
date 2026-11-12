import { lazy, type LazyExoticComponent, type ComponentType } from 'react';

/** 原型唯一登记点：索引页与路由都由本表派生；新增原型只改这里。 */

export interface ProtoEntry {
  id: string;
  title: string;
  domain: string;
  desc: string;
  component: LazyExoticComponent<ComponentType>;
}

export const PROTO_DOMAINS = [
  '认证域',
  '发布单域',
  '发布工程域',
  '流程配置域',
  '观测域',
  '可视化看板',
  '协作域',
  '用户域',
  '环境域',
  '稳定性域',
  '集成域',
  '内容与治理域',
  '应用与配置域',
  '横向专项',
] as const;

export const PROTO_REGISTRY: ProtoEntry[] = [
  // 认证域 L
  { id: 'login', title: '登录页', domain: '认证域', desc: 'Acme SaaS 云平台 · 多租户登录：租户代码 / 品牌区 / 表单 / 四态走查（L1）', component: lazy(() => import('./pages/Login')) },
  // 发布单域 A
  { id: 'approval', title: '审批详情', domain: '发布单域', desc: '审批链、意见流与通过/驳回（A1）', component: lazy(() => import('./pages/ApprovalDetail')) },
  { id: 'build-log', title: '构建日志', domain: '发布单域', desc: '分阶段/分级过滤的日志视图（A2）', component: lazy(() => import('./pages/BuildLog')) },
  { id: 'release-detail', title: '发布单详情全屏', domain: '发布单域', desc: '完整时间线 / 产物 / 关联 / 风险（A3）', component: lazy(() => import('./pages/ReleaseDetail')) },
  { id: 'calendar', title: '发布日历', domain: '发布单域', desc: '排期矩阵与冲突检测（A4）', component: lazy(() => import('./pages/ReleaseCalendar')) },
  { id: 'rollback', title: '回滚向导', domain: '发布单域', desc: '选版本 → 安全确认 → 执行（A5）', component: lazy(() => import('./pages/RollbackWizard')) },
  { id: 'orchestration', title: '批量编排', domain: '发布单域', desc: '依赖拓扑分批与成环检测（A6）', component: lazy(() => import('./pages/Orchestration')) },
  // 流程配置域 B
  { id: 'approval-flow', title: '审批流配置', domain: '流程配置域', desc: '按环境的审批规则编辑（B1）', component: lazy(() => import('./pages/ApprovalFlowConfig')) },
  { id: 'templates', title: '流水线模板', domain: '流程配置域', desc: '阶段编排模板与耗时预估（B2）', component: lazy(() => import('./pages/PipelineTemplates')) },
  { id: 'promotion', title: '环境晋升看板', domain: '流程配置域', desc: 'dev → test → prod 看板流转（B3）', component: lazy(() => import('./pages/PromotionBoard')) },
  // 观测域 C
  { id: 'reports', title: '数据报表', domain: '观测域', desc: '趋势 / 成功率 / 归因 / 耗时（C1）', component: lazy(() => import('./pages/Reports')) },
  { id: 'live-board', title: '实时大盘', domain: '观测域', desc: '进行中流水线与构建队列（C2）', component: lazy(() => import('./pages/LiveBoard')) },
  { id: 'audit', title: '活动审计', domain: '观测域', desc: '操作留痕与多维筛选（C3）', component: lazy(() => import('./pages/AuditTrail')) },
  // 协作域 D
  { id: 'members', title: '成员与角色', domain: '协作域', desc: '成员卡片与权限矩阵（D1）', component: lazy(() => import('./pages/Members')) },
  { id: 'notifications', title: '通知中心', domain: '协作域', desc: '分类收件箱与已读管理（D2）', component: lazy(() => import('./pages/Notifications')) },
  { id: 'comments', title: '发布单评论', domain: '协作域', desc: '讨论 thread 与 @ 提及（D3）', component: lazy(() => import('./pages/Comments')) },
  // 应用与配置域 E
  { id: 'app-detail', title: '应用详情', domain: '应用与配置域', desc: '健康曲线与环境变量脱敏（E1）', component: lazy(() => import('./pages/AppDetail')) },
  { id: 'new-app', title: '新建应用向导', domain: '应用与配置域', desc: '平台 → 仓库 → 确认三步创建（E2）', component: lazy(() => import('./pages/NewAppWizard')) },
  { id: 'settings-ext', title: '设置扩展', domain: '应用与配置域', desc: '主题预览 / 快捷键 / 多语言（E3）', component: lazy(() => import('./pages/SettingsExt')) },
  // 横向专项 F
  { id: 'states', title: '全局状态规范', domain: '横向专项', desc: '空态 / 加载态 / 错误态规格（F1）', component: lazy(() => import('./pages/StatesDemo')) },
  { id: 'theme-matrix', title: '主题矩阵走查', domain: '横向专项', desc: '6 强调色 × 暗/亮组件标本（F5）', component: lazy(() => import('./pages/ThemeMatrix')) },
  // 可视化看板 V（增量二）
  { id: 'wall', title: '数据大屏', domain: '可视化看板', desc: '趋势/漏斗/热力/节点/构建一屏总览（V0）', component: lazy(() => import('./pages/WallDashboard')) },
  { id: 'pipeline', title: '阶段管道图', domain: '可视化看板', desc: '发布单阶段链路可视化（V1）', component: lazy(() => import('./pages/PipelineFlow')) },
  { id: 'heatmap', title: '发布热力图', domain: '可视化看板', desc: '按日活动强度格（V2）', component: lazy(() => import('./pages/Heatmap')) },
  { id: 'funnel', title: '发布漏斗', domain: '可视化看板', desc: '在途阶段留存漏斗（V3）', component: lazy(() => import('./pages/FunnelView')) },
  { id: 'radar', title: '应用健康雷达', domain: '可视化看板', desc: '三端五维对比（V4）', component: lazy(() => import('./pages/RadarCompare')) },
  { id: 'gantt', title: '版本时间线', domain: '可视化看板', desc: '甘特式发布跨度（V5）', component: lazy(() => import('./pages/GanttTimeline')) },
  { id: 'nodes', title: '节点状态墙', domain: '可视化看板', desc: '构建集群格子墙（V6）', component: lazy(() => import('./pages/NodeWall')) },
  // 用户域 U（增量三）
  { id: 'users', title: '用户列表', domain: '用户域', desc: '成员目录与工作负荷（U1）', component: lazy(() => import('./pages/UserDirectory')) },
  { id: 'user-detail', title: '用户详情', domain: '用户域', desc: '个人档案/审批/留痕（U2）', component: lazy(() => import('./pages/UserDetail')) },
  { id: 'my-desk', title: '我的工作台', domain: '用户域', desc: '待办/进行中/动态聚合（U3）', component: lazy(() => import('./pages/MyDesk')) },
  // 发布工程域 R（增量四）
  { id: 'readiness', title: '发布就绪度检查', domain: '发布工程域', desc: 'Go/No-Go 六项门禁矩阵（R1）', component: lazy(() => import('./pages/ReadinessCheck')) },
  { id: 'create-wizard', title: '新建发布单向导', domain: '发布工程域', desc: '四步创建形态探索（R2）', component: lazy(() => import('./pages/CreateWizard')) },
  { id: 'artifacts', title: '制品库', domain: '发布工程域', desc: '跨单产物聚合与检索（R3）', component: lazy(() => import('./pages/ArtifactLibrary')) },
  { id: 'version-diff', title: '版本对比', domain: '发布工程域', desc: '字段级 diff（R4）', component: lazy(() => import('./pages/VersionDiff')) },
  // 环境域 H（增量四）
  { id: 'envs', title: '环境中心', domain: '环境域', desc: 'dev/test/prod 概览与详情（H1）', component: lazy(() => import('./pages/EnvCenter')) },
  { id: 'topology', title: '部署拓扑图', domain: '环境域', desc: '应用→渠道依赖关系（H2）', component: lazy(() => import('./pages/TopologyMap')) },
  // 稳定性域 S（增量四）
  { id: 'incidents', title: '事故与事件流', domain: '稳定性域', desc: '失败→事故单 + MTTR（S1）', component: lazy(() => import('./pages/IncidentStream')) },
  { id: 'gray', title: '灰度发布管理', domain: '稳定性域', desc: '5%→50%→100% 放量控制（S2）', component: lazy(() => import('./pages/GrayRelease')) },
  // 集成域 G（增量四）
  { id: 'integrations', title: '集成中心', domain: '集成域', desc: '外部系统与通知渠道（G1）', component: lazy(() => import('./pages/IntegrationHub')) },
  // 横向专项 X（增量四）
  { id: 'search', title: '全局搜索中心', domain: '横向专项', desc: '聚合搜索结果页（X1）', component: lazy(() => import('./pages/SearchCenter')) },
  { id: 'components', title: '通用组件样例库', domain: '横向专项', desc: '8 缺口组件用例与规格（F7）', component: lazy(() => import('./pages/ComponentGallery')) },
  { id: 'advanced-table', title: '表格进阶模式', domain: '横向专项', desc: '展开行/列自定义/密度/固定列（X2）', component: lazy(() => import('./pages/AdvancedTable')) },
  { id: 'onboarding', title: '新手引导清单', domain: '横向专项', desc: '上手六步自动+手动判定（X3）', component: lazy(() => import('./pages/Onboarding')) },
  // 内容与治理域 W（增量六）
  { id: 'announcements', title: '公告与规范中心', domain: '内容与治理域', desc: '公告 + Markdown 编辑 + 文档索引（W1）', component: lazy(() => import('./pages/AnnouncementCenter')) },
  { id: 'feature-flags', title: '功能开关管理', domain: '内容与治理域', desc: '运行时开关与影响面（W2）', component: lazy(() => import('./pages/FeatureFlags')) },
  { id: 'org-manage', title: '组织架构管理', domain: '内容与治理域', desc: '部门树增删与归属（W3）', component: lazy(() => import('./pages/OrgManage')) },
  { id: 'backups', title: '备份与迁移中心', domain: '内容与治理域', desc: '快照/导出/导入预览确认（W4）', component: lazy(() => import('./pages/BackupCenter')) },
];

export const findProto = (id: string | undefined): ProtoEntry | undefined =>
  PROTO_REGISTRY.find((e) => e.id === id);
