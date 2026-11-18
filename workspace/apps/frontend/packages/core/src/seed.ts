import type { AppState, App, Release, Platform, Env, Status } from './types';

const hoursAgo = (now: number, h: number): string =>
  new Date(now - h * 3600_000).toISOString();

type SeedRow = [
  id: string,
  title: string,
  appId: Platform,
  version: string,
  env: Env,
  status: Status,
  priority: number,
  owner: string,
  age: number,
  progress: number,
];

export function seedState(now: number = Date.now()): AppState {
  const apps: App[] = [
    {
      id: 'web',
      name: 'Web 控制台',
      platform: 'web',
      path: 'workspace/apps/frontend/web',
      version: '1.4.2',
      owner: '林晚',
      health: 'healthy',
      description: '浏览器端页面与业务逻辑的唯一来源，桌面端复用其构建产物。',
    },
    {
      id: 'desktop',
      name: 'Electron 桌面端',
      platform: 'desktop',
      path: 'workspace/apps/frontend/desktop',
      version: '1.4.2',
      owner: '陈屿',
      health: 'healthy',
      description: 'Electron 套壳 Web 端，只维护主进程、预加载桥接与打包资源。',
    },
    {
      id: 'mobile',
      name: '移动端',
      platform: 'mobile',
      path: 'workspace/apps/frontend/mobile',
      version: '1.3.0',
      owner: '苏然',
      health: 'warning',
      description: 'React Native + Expo 移动入口，复用共享 core 业务层。',
    },
  ];

  const raw: SeedRow[] = [
    ['REL-2412', '登录页视觉升级', 'web', '1.4.3', 'test', 'testing', 1, '林晚', 3, 62],
    ['REL-2411', '桌面端自动更新通道', 'desktop', '1.4.3', 'test', 'building', 2, '陈屿', 5, 38],
    ['REL-2410', '移动端离线缓存', 'mobile', '1.3.1', 'dev', 'building', 3, '苏然', 7, 71],
    ['REL-2409', '首屏性能优化', 'web', '1.4.2', 'prod', 'published', 4, '林晚', 26, 100],
    ['REL-2408', 'Electron 窗口状态记忆', 'desktop', '1.4.2', 'prod', 'published', 5, '陈屿', 30, 100],
    ['REL-2407', '深色主题对比度修复', 'web', '1.4.2', 'test', 'approved', 6, '周衍', 9, 100],
    ['REL-2406', '移动端手势导航', 'mobile', '1.3.0', 'test', 'failed', 7, '苏然', 12, 84],
    ['REL-2405', '构建缓存策略调整', 'web', '1.4.1', 'prod', 'published', 8, '周衍', 50, 100],
    ['REL-2404', '崩溃上报接入', 'desktop', '1.4.1', 'test', 'testing', 9, '陈屿', 18, 100],
    ['REL-2403', '接口超时重试', 'web', '1.4.1', 'dev', 'draft', 10, '何青', 2, 0],
    ['REL-2402', '图标资源压缩', 'mobile', '1.2.9', 'prod', 'published', 11, '苏然', 74, 100],
    ['REL-2401', '发布审批流接入', 'desktop', '1.4.0', 'prod', 'cancelled', 12, '陈屿', 40, 45],
    ['REL-2400', '多语言文案校对', 'web', '1.4.0', 'test', 'approved', 13, '何青', 22, 100],
    ['REL-2399', '测试环境灰度开关', 'mobile', '1.2.8', 'dev', 'draft', 14, '周衍', 1, 0],
  ];

  const releases: Release[] = raw.map(
    ([id, title, appId, version, env, status, priority, owner, age, progress]) => {
      const app = apps.find((a) => a.id === appId)!;
      const createdAt = hoursAgo(now, age + 6);
      const updatedAt = hoursAgo(now, age);
      const timeline = [{ at: createdAt, action: '创建发布单', by: owner }];
      if (status !== 'draft') timeline.push({ at: hoursAgo(now, age + 4), action: '开始构建', by: owner });
      if (['testing', 'approved', 'published', 'failed'].includes(status)) {
        timeline.push({ at: hoursAgo(now, age + 2), action: '进入测试', by: 'CI' });
      }
      if (['approved', 'published'].includes(status)) {
        timeline.push({ at: hoursAgo(now, age + 1), action: '审批通过', by: '何青' });
      }
      if (status === 'published') timeline.push({ at: updatedAt, action: '发布完成', by: owner });
      if (status === 'failed') timeline.push({ at: updatedAt, action: '测试失败：快照回归不一致', by: 'CI' });
      if (status === 'cancelled') timeline.push({ at: updatedAt, action: '取消发布', by: owner });
      const artifacts = ['published', 'approved', 'testing'].includes(status)
        ? [
            {
              name: `${appId}-${version}.zip`,
              size: appId === 'desktop' ? '92.4 MB' : appId === 'mobile' ? '31.8 MB' : '8.6 MB',
            },
          ]
        : [];
      return {
        id,
        title,
        appId,
        platform: app.platform,
        version,
        env,
        status,
        priority,
        owner,
        progress,
        notes:
          status === 'failed'
            ? '移动端手势导航在 iOS 小屏设备上与系统返回手势冲突，需要补充安全区域适配。'
            : '按多端交付流程执行：构建 → 测试 → 审批 → 发布。桌面端产物由 Web 构建输出注入。',
        artifacts,
        timeline,
        createdAt,
        updatedAt,
      };
    },
  );

  return {
    version: 1,
    apps,
    releases,
    order: releases.map((r) => r.id),
    savedViews: [
      {
        id: 'view-active',
        name: '进行中',
        query: { search: '', platform: 'all', status: 'active', env: 'all', sort: 'manual' },
      },
      {
        id: 'view-prod',
        name: '生产发布',
        query: { search: '', platform: 'all', status: 'all', env: 'prod', sort: 'updated' },
      },
    ],
    settings: { theme: 'dark', density: 'comfortable', accent: 'cyan', motion: true },
    activity: [
      { id: 'act-1', at: hoursAgo(now, 0.4), text: 'REL-2412 进入测试阶段', tone: 'violet' },
      { id: 'act-2', at: hoursAgo(now, 1.2), text: 'REL-2411 构建进度 38%', tone: 'info' },
      { id: 'act-3', at: hoursAgo(now, 2.5), text: 'REL-2407 审批通过，等待发布', tone: 'warning' },
      { id: 'act-4', at: hoursAgo(now, 5), text: 'REL-2406 测试失败，已通知负责人', tone: 'danger' },
      { id: 'act-5', at: hoursAgo(now, 26), text: 'REL-2409 已发布到生产环境', tone: 'success' },
    ],
  };
}