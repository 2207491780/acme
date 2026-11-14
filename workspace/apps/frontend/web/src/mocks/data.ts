import type {
  AdminAuditEventView,
  AdminDictItemView,
  AdminDictTypeView,
  AdminMenuView,
  AdminMessageView,
  AdminNoticeView,
  AdminPermissionView,
  AdminRoleView,
  AdminTenantView,
  AdminUserView,
  FileView,
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
    status: 'ENABLED',
    builtIn: true,
    roleIds: ['1'],
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    version: '0',
  } satisfies AdminUserView,
  roles: ['super_admin'],
  permissions: [
    'system:tenant:list', 'system:tenant:create', 'system:tenant:update',
    'system:user:list', 'system:user:create', 'system:user:update', 'system:user:delete', 'system:user:assign-role',
    'system:role:list', 'system:role:create', 'system:role:update', 'system:role:delete', 'system:role:grant',
    'system:permission:list', 'system:permission:create', 'system:permission:update', 'system:permission:delete',
    'system:menu:list', 'system:menu:create', 'system:menu:update', 'system:menu:delete',
    'system:dict:list', 'system:dict:create', 'system:dict:update', 'system:dict:delete',
    'system:notice:list', 'system:notice:create', 'system:notice:update', 'system:notice:delete', 'system:notice:publish',
    'system:message:list', 'system:message:read',
    'infra:audit:list', 'infra:file:list', 'infra:file:upload', 'infra:file:download', 'infra:file:delete',
  ],
  menus: [] as AdminMenuView[],
};

export const tenants: AdminTenantView[] = [
  { id: '1', tenantCode: 'acme', tenantName: 'ACME 总部', status: 'ENABLED', remark: null, createdAt: '2026-01-01T00:00:00Z', version: '0' },
  { id: '2', tenantCode: 'beta', tenantName: 'Beta 试点', status: 'ENABLED', remark: null, createdAt: '2026-01-02T00:00:00Z', version: '0' },
];

export const users: AdminUserView[] = [
  {
    id: '1001', username: 'admin', nickname: '超级管理员', email: 'admin@acme.local', phone: null,
    status: 'ENABLED', builtIn: true, roleIds: ['1'], createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', version: '0',
  },
  {
    id: '1002', username: 'ops', nickname: '运维员', email: null, phone: null,
    status: 'ENABLED', builtIn: false, roleIds: ['2'], createdAt: '2026-01-03T00:00:00Z', updatedAt: '2026-01-04T00:00:00Z', version: '0',
  },
];

export const roles: AdminRoleView[] = [
  { id: '1', parentId: null, roleCode: 'super_admin', roleName: '超级管理员', status: 'ENABLED', builtIn: true, remark: null, version: '0' },
  { id: '2', parentId: '1', roleCode: 'ops', roleName: '运维', status: 'ENABLED', builtIn: false, remark: null, version: '0' },
];

export const permissions: AdminPermissionView[] = [
  { id: '1', permissionCode: 'system:user:list', permissionName: '用户查询', permissionType: 'API', httpMethod: 'GET', httpPath: '/admin/v1/system/users', status: 'ENABLED', version: '0' },
  { id: '2', permissionCode: 'system:user:create', permissionName: '用户新建', permissionType: 'API', httpMethod: 'POST', httpPath: '/admin/v1/system/users', status: 'ENABLED', version: '0' },
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
