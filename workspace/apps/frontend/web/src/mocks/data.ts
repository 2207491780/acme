import type {
  AdminAuditEventView,
  AdminDeptView,
  AdminDictItemView,
  AdminDictTypeView,
  AdminLoginLogView,
  AdminMenuView,
  AdminMessageView,
  AdminNoticeView,
  AdminOnlineUserView,
  AdminOpExceptionView,
  AdminPermissionView,
  AdminPositionView,
  AdminRoleView,
  AdminSystemConfigView,
  AdminTenantView,
  AdminUserView,
  FileView,
  JobExecutionView,
  JobView,
} from '@acme/core';

/**
 * MSW 内存种子数据（REQ-20261114-120103，AC-7）：形状与 @acme/core 视图类型逐字段对齐，
 * 供 dev mock 与测试 handler 共用；可变集合以最小 CRUD 支持 dev 浏览演示。
 */

/** 1x1 透明 PNG（captcha 图形占位，data-URI 直渲染）。 */
export const CAPTCHA_IMAGE_DATA = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==';

export const me = {
  user: {
    id: '1001',
    username: 'admin',
    nickname: '超级管理员',
    email: 'admin@acme.local',
    phone: null,
    avatarUrl: null,
    status: 'ENABLED',
    builtIn: true,
    roleIds: ['1'],
    deptId: '4001',
    positionId: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    version: '0',
  } satisfies AdminUserView,
  roles: ['super_admin'],
  permissions: [
    'system:tenant:list', 'system:tenant:create', 'system:tenant:update',
    'system:user:list', 'system:user:create', 'system:user:update', 'system:user:delete', 'system:user:assign-role',
    'system:role:list', 'system:role:create', 'system:role:update', 'system:role:delete', 'system:role:grant', 'system:role:scope',
    'system:dept:list', 'system:dept:create', 'system:dept:update', 'system:dept:delete',
    'system:position:list', 'system:position:create', 'system:position:update', 'system:position:delete',
    'system:permission:list', 'system:permission:create', 'system:permission:update', 'system:permission:delete',
    'system:menu:list', 'system:menu:create', 'system:menu:update', 'system:menu:delete',
    'system:dict:list', 'system:dict:create', 'system:dict:update', 'system:dict:delete',
    'system:notice:list', 'system:notice:create', 'system:notice:update', 'system:notice:delete', 'system:notice:publish',
    'system:config:list', 'system:config:update',
    'system:message:list', 'system:message:read',
    'infra:audit:list', 'infra:file:list', 'infra:file:upload', 'infra:file:download', 'infra:file:delete',
    'infra:exception:list', 'infra:job:list', 'infra:job:run',
    'auth:loginlog:list', 'auth:online:list', 'auth:online:revoke',
  ],
  menus: [] as AdminMenuView[],
  // REQ-20261117-180000：本人部门名（deptId 4001=总部；水印 {deptName} 供给）
  deptName: '总部',
};

/** 租户系统配置种子（REQ-20261116-230354）：缺省=水印开/闲置锁屏关/version "0"；
 *  REQ-20261117-011514 增验证码开关（V16 内置默认关闭）；
 *  REQ-20261117-025200 增页脚版权（V17 内置默认 copyright=null/visible=false）。 */
export const systemConfig: AdminSystemConfigView = {
  watermarkEnabled: true, captchaEnabled: false, lockIdleTimeoutMs: 0,
  footerCopyright: null, footerVisible: false, watermarkTemplate: null, version: '0',
};

export const tenants: AdminTenantView[] = [
  {
    id: '1', tenantCode: 'acme', tenantName: 'ACME 总部', status: 'ENABLED', remark: null,
    contactName: '平台管理员', contactPhone: '13800000000', expiresAt: null,
    createdAt: '2026-01-01T00:00:00Z', version: '0',
  },
  {
    id: '2', tenantCode: 'beta', tenantName: 'Beta 试点', status: 'ENABLED', remark: null,
    contactName: null, contactPhone: null, expiresAt: null,
    createdAt: '2026-01-02T00:00:00Z', version: '0',
  },
];

export const users: AdminUserView[] = [
  {
    id: '1001', username: 'admin', nickname: '超级管理员', email: 'admin@acme.local', phone: null,
    status: 'ENABLED', builtIn: true, roleIds: ['1'], deptId: '4001', positionId: null,
    createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', version: '0',
  },
  {
    id: '1002', username: 'ops', nickname: '运维员', email: null, phone: null,
    status: 'ENABLED', builtIn: false, roleIds: ['2'], deptId: '4002', positionId: '5003',
    createdAt: '2026-01-03T00:00:00Z', updatedAt: '2026-01-04T00:00:00Z', version: '0',
  },
];

export const roles: AdminRoleView[] = [
  { id: '1', parentId: null, roleCode: 'super_admin', roleName: '超级管理员', status: 'ENABLED', builtIn: true, remark: null, dataScope: 'ALL', version: '0' },
  { id: '2', parentId: '1', roleCode: 'ops', roleName: '运维', status: 'ENABLED', builtIn: false, remark: null, dataScope: 'DEPT', version: '0' },
];

/** 部门树（REQ-20261114-120104）：两级树（总部↘研发部）承载父子层级；财务部承载禁用态。 */
export const depts: AdminDeptView[] = [
  {
    id: '4001', parentId: '0', deptName: '总部', sortOrder: 1, status: 'ENABLED', version: '0',
    children: [
      { id: '4002', parentId: '4001', deptName: '研发部', sortOrder: 1, status: 'ENABLED', version: '0', children: [] },
    ],
  },
  { id: '4003', parentId: '0', deptName: '市场部', sortOrder: 2, status: 'ENABLED', version: '0', children: [] },
  { id: '4004', parentId: '0', deptName: '财务部', sortOrder: 3, status: 'DISABLED', version: '0', children: [] },
  { id: '4005', parentId: '0', deptName: '行政部', sortOrder: 4, status: 'ENABLED', version: '0', children: [] },
];

/** 岗位清单（REQ-20261114-120104）：前端工程师被 ops 引用（POSITION_IN_USE 演示）。 */
export const positions: AdminPositionView[] = [
  { id: '5001', positionCode: 'frontend_eng', positionName: '前端工程师', status: 'ENABLED', createdAt: '2026-01-01T00:00:00Z', version: '0' },
  { id: '5002', positionCode: 'product_mgr', positionName: '产品经理', status: 'ENABLED', createdAt: '2026-01-02T00:00:00Z', version: '0' },
  { id: '5003', positionCode: 'qa_eng', positionName: '测试工程师', status: 'ENABLED', createdAt: '2026-01-03T00:00:00Z', version: '0' },
];

/** 权限点样例（REQ-20261120-214653）：含 1 条 DISABLED（用户删除）保证状态筛选可演示命中；
 *  httpMethod 覆盖 GET/POST/DELETE 三动词。 */
export const permissions: AdminPermissionView[] = [
  { id: '1', permissionCode: 'system:user:list', permissionName: '用户查询', permissionType: 'API', httpMethod: 'GET', httpPath: '/admin/v1/system/users', status: 'ENABLED', version: '0' },
  { id: '2', permissionCode: 'system:user:create', permissionName: '用户新建', permissionType: 'API', httpMethod: 'POST', httpPath: '/admin/v1/system/users', status: 'ENABLED', version: '0' },
  { id: '3', permissionCode: 'system:user:delete', permissionName: '用户删除', permissionType: 'API', httpMethod: 'DELETE', httpPath: '/admin/v1/system/users/{id}', status: 'DISABLED', version: '0' },
];

export const menus: AdminMenuView[] = [
  {
    id: 1, parentId: 0, menuName: '系统管理', menuType: 'DIRECTORY', path: null, component: null, icon: 'setting',
    sortOrder: 1, visible: true, permissionCode: null, status: 'ENABLED', version: '0',
    children: [
      {
        id: 11, parentId: 1, menuName: '用户管理', menuType: 'MENU', path: '/users', component: 'users', icon: 'users',
        sortOrder: 1, visible: true, permissionCode: 'system:user:list', status: 'ENABLED', version: '0', children: [],
      },
      {
        id: 12, parentId: 1, menuName: '系统配置', menuType: 'MENU', path: '/system/config', component: 'system/config/index', icon: 'control',
        sortOrder: 2, visible: true, permissionCode: 'system:config:list', status: 'ENABLED', version: '0', children: [],
      },
      // REQ-20261120-213603：补停用/隐藏样例（菜单筛选演示命中；nav 渲染剔除 DISABLED/隐藏节点，不影响导航）
      {
        id: 13, parentId: 1, menuName: '任务调度', menuType: 'MENU', path: '/jobs', component: 'jobs/index', icon: 'clock-circle',
        sortOrder: 3, visible: true, permissionCode: 'system:job:list', status: 'DISABLED', version: '0', children: [],
      },
      {
        id: 14, parentId: 1, menuName: '文件管理', menuType: 'MENU', path: '/files', component: 'files/index', icon: 'folder',
        sortOrder: 4, visible: false, permissionCode: null, status: 'ENABLED', version: '0', children: [],
      },
    ],
  },
];

export const dictTypes: AdminDictTypeView[] = [
  { id: '8801', dictCode: 'release_env', dictName: '发布环境', status: 'ENABLED', remark: null, version: '0' },
];

export const dictItems: AdminDictItemView[] = [
  { id: '88011', dictTypeId: '8801', itemLabel: '开发', itemValue: 'dev', sortOrder: 1, status: 'ENABLED', version: '0' },
  { id: '88012', dictTypeId: '8801', itemLabel: '生产', itemValue: 'prod', sortOrder: 2, status: 'ENABLED', version: '0' },
];

export const notices: AdminNoticeView[] = [
  {
    id: '9001', title: '平台上线通知', content: '<p>ACME 平台已上线。</p>', status: 'PUBLISHED',
    publishAt: '2026-01-05T08:00:00Z', createdAt: '2026-01-05T07:00:00Z', updatedAt: '2026-01-05T08:00:00Z', version: '1',
  },
];

export const messages: AdminMessageView[] = [
  {
    id: '7001', senderId: '0', title: '欢迎加入 ACME', content: '<p>欢迎使用站内信。</p>', isRead: false,
    readAt: null, createdAt: '2026-01-05T08:30:00Z', version: '0',
  },
];

export const auditEvents: AdminAuditEventView[] = [
  {
    id: '6001', eventId: 'e-6001', traceId: 'trace-abc', spanId: 'span-1', tenantId: '1', operatorId: '1001',
    channel: 'admin', action: 'release.create', targetType: 'release', targetId: 'r-1', content: '创建发布单',
    occurredAt: '2026-01-05T09:00:00Z', createdAt: '2026-01-05T09:00:01Z',
  },
];

export const files: FileView[] = [
  {
    id: '5001', originalName: 'demo-export.xlsx', size: 10240, contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    url: null, bizType: null, bizId: null, createdAt: '2026-01-05T10:00:00Z',
  },
];

/** 登录日志 fixtures（REQ-20261117-061017：登录日志页/导出 dev mock；形状=AdminLoginLogView）。 */
export const loginLogs: AdminLoginLogView[] = [
  {
    id: '6101', username: 'admin', userType: 'B', ip: '10.0.0.9', userAgent: 'mock-agent-admin',
    result: 'SUCCESS', failReason: null, traceId: 'trace-login-1', createdAt: '2026-11-17T01:00:00Z',
  },
  {
    id: '6102', username: 'ops', userType: 'B', ip: '10.0.0.10', userAgent: 'mock-agent-ops',
    result: 'FAIL', failReason: '密码错误', traceId: 'trace-login-2', createdAt: '2026-11-17T02:00:00Z',
  },
];

/** 在线用户 fixtures（REQ-20261117-061017：批量下线 dev mock；admin=超管会话跳过口径）。 */
export const onlineUsers: AdminOnlineUserView[] = [
  {
    sessionId: 'sess-op-1', username: 'admin', userType: 'B', ip: '10.0.0.9',
    loginAt: '2026-11-17T01:00:00Z', lastActiveAt: '2026-11-17T03:00:00Z',
  },
  {
    sessionId: 'sess-op-2', username: 'ops', userType: 'B', ip: '10.0.0.10',
    loginAt: '2026-11-17T02:00:00Z', lastActiveAt: '2026-11-17T03:10:00Z',
  },
];

/** 运行异常日志 fixtures（REQ-20261117-061017：列表/详情/导出 dev mock；详情含 stackSummary 全文）。 */
export const opExceptions: AdminOpExceptionView[] = [
  {
    id: '6201', service: 'platform-infra-service', traceId: 'trace-exc-1', spanId: 'span-exc-1',
    tenantId: '1', operatorId: '1001', method: 'POST', path: '/admin/v1/infra/files', statusCode: 500,
    exceptionType: 'java.lang.IllegalStateException',
    message: '存储初始化失败',
    stackSummary: 'java.lang.IllegalStateException: 存储初始化失败\n\tat com.acme.infra.StorageBootstrap.run(StorageBootstrap.java:42)\n\tat java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)',
    occurredAt: '2026-11-17T04:00:00Z',
  },
  {
    id: '6202', service: 'platform-system-service', traceId: 'trace-exc-2', spanId: 'span-exc-2',
    tenantId: '1', operatorId: '1002', method: 'GET', path: '/admin/v1/system/users', statusCode: 500,
    exceptionType: 'org.springframework.dao.DataAccessException',
    message: '数据访问还原失败',
    stackSummary: 'org.springframework.dao.DataAccessException: 数据访问还原失败\n\tat com.acme.system.UserQueryUseCase.list(UserQueryUseCase.java:88)',
    occurredAt: '2026-11-17T05:00:00Z',
  },
];

/** 定时任务 fixtures（REQ-20261117-061017：契约对齐后端 JobView record——key/enabled/嵌套 lastExecution）。 */
export const jobs: JobView[] = [
  {
    key: 'file-retention-cleanup', description: '过期文件保留期清理', cron: '0 0 3 * * ?', enabled: true,
    lastExecution: { status: 'SUCCESS', startedAt: '2026-11-17T03:00:00Z', finishedAt: '2026-11-17T03:00:02Z', error: null },
  },
  {
    key: 'exception-log-retention-cleanup', description: '异常日志保留期清理', cron: '0 40 3 * * ?', enabled: true,
    lastExecution: { status: 'FAILED', startedAt: '2026-11-17T03:40:00Z', finishedAt: '2026-11-17T03:40:01Z', error: '模拟执行失败（fixture）' },
  },
  {
    key: 'audit-retention-cleanup', description: '审计日志保留期清理', cron: '0 40 4 * * ?', enabled: true,
    lastExecution: null,
  },
];

/** 任务执行记录 fixtures（REQ-20261117-061017：执行记录抽屉 dev mock；started_at 倒序由 handler 排序）。 */
export const jobExecutions: JobExecutionView[] = [
  {
    id: '6301', jobKey: 'file-retention-cleanup', status: 'SUCCESS',
    startedAt: '2026-11-17T03:00:00Z', finishedAt: '2026-11-17T03:00:02Z',
    error: null, triggerType: 'SCHEDULE', durationMs: 2000,
  },
  {
    id: '6302', jobKey: 'file-retention-cleanup', status: 'FAILED',
    startedAt: '2026-11-16T03:00:00Z', finishedAt: '2026-11-16T03:00:01Z',
    error: '模拟执行失败（fixture）', triggerType: 'SCHEDULE', durationMs: 800,
  },
  {
    id: '6303', jobKey: 'exception-log-retention-cleanup', status: 'FAILED',
    startedAt: '2026-11-17T03:40:00Z', finishedAt: '2026-11-17T03:40:01Z',
    error: '模拟执行失败（fixture）', triggerType: 'SCHEDULE', durationMs: 900,
  },
];
