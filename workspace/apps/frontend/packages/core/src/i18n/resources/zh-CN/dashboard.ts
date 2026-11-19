/** zh-CN 概览页资源包（Dashboard）。 */
export const dashboard = {
  hero: {
    eyebrow: 'ACME · Frontend Delivery',
    title: '多端交付，一屏掌控',
    desc: '统一管理 Web、Electron 桌面端与移动端的发布单。桌面端复用 Web 构建产物，移动端共享业务层，从构建、测试、审批到发布全程可追踪。',
  },
  stats: {
    active: '进行中',
    approved: '待发布',
    publishedToday: '今日发布',
    failed: '失败待处理',
  },
  stages: {
    draft: '草稿',
    building: '构建',
    testing: '测试',
    approved: '审批',
    published: '发布',
  },
  cards: {
    pipeline: '交付流水线',
    pipelineTotal: '共 {{total}} 个发布单',
    statusDistribution: '发布状态分布',
    trendChart: '近 14 日登录趋势',
    distributionChart: '审计动作分布',
    recent: '最近更新',
    activity: '动态',
    recentAll: '全部',
  },
  actions: {
    createRelease: '新建发布单',
    viewAll: '查看全部发布',
    apps: '应用结构',
    files: '文件管理',
  },
  overview: {
    title: '平台概览',
    refresh: '刷新',
    stale: '数据可能已过期，建议刷新',
    users: '用户总数',
    roles: '角色总数',
    tenants: '租户总数',
    online: '在线用户',
    unread: '未读消息',
    logins14d: '近 14 日登录',
  },
  notices: {
    title: '已发布公告',
    empty: '暂无发布公告',
    viewAll: '全部公告',
  },
  quickAccess: {
    title: '快捷入口',
  },
  noRelease: '暂无发布',
  mobile: {
    heroDesc: '统一管理 Web、Electron 桌面端与移动端的发布单。移动端共享 @acme/core 业务层，从构建、测试、审批到发布全程可追踪。',
    recentActivity: '最近动态',
  },
};
