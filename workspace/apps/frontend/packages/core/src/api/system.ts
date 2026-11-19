import { apiFetch } from '../http/client';
import type { FileView } from './infra';

/** 分页信封（契约对齐 PageResult；total 为 Long——按数字容错解析）。 */
export interface PageResult<T> {
  records: T[];
  total: number;
}

/** 用户视图（契约对齐 AdminUserView；id/version/roleIds 序列化为字符串）。
 *  REQ-20261114-120104 扩展 deptId/positionId（可空：存量数据未分配）。
 *  REQ-20261119-082531 扩展 avatarUrl（可空：V21 前存量用户无头像）。 */
export interface AdminUserView {
  id: string;
  username: string;
  nickname: string | null;
  email: string | null;
  phone: string | null;
  avatarUrl?: string | null;
  status: 'ENABLED' | 'DISABLED';
  builtIn: boolean;
  roleIds: string[];
  deptId?: string | null;
  positionId?: string | null;
  createdAt: string;
  updatedAt: string;
  version: string;
}

/** 菜单节点（树形，children 嵌套；契约对齐 AdminMenuView）。 */
export interface AdminMenuView {
  id: number;
  parentId: number;
  menuName: string;
  menuType: string;
  path: string | null;
  component: string | null;
  icon: string | null;
  sortOrder: number | null;
  visible: boolean | null;
  permissionCode: string | null;
  status: 'ENABLED' | 'DISABLED';
  children: AdminMenuView[];
  version: string;
}

/** 当前登录者视图（契约对齐 AdminMeView；FR-008 权限声明与菜单可见性依据）。
 *  REQ-20261117-180000 扩展 deptName（本人部门名，水印 {deptName} 供给；未分配/缺失=null）。 */
export interface AdminMeView {
  user: AdminUserView;
  roles: string[];
  permissions: string[];
  menus: AdminMenuView[];
  deptName: string | null;
}

/** 本人资料更新载荷（REQ-20261119-082531 契约①）：nickname 必填 ≤32；email 可空（email 格式）、
 *  phone 可空（1\d{10}），null=清空该字段，校验对齐 UserController 既有口径；
 *  avatarUrl 由头像两步上传流程供给（可空）。 */
export interface MeUpdatePayload {
  nickname: string;
  email?: string | null;
  phone?: string | null;
  avatarUrl?: string | null;
}

/** 用户通知偏好视图（REQ-20261119-082531 契约⑤；sys_user_preference.prefs jsonb，本人单行 upsert）。 */
export interface UserPreferenceView {
  messagePush: boolean;
}

/** 登录趋势点（REQ-20261119-011524 契约①，对齐 LoginTrendPoint；date=YYYY-MM-DD 按日桶）。 */
export interface DashboardTrendPoint {
  date: string;
  count: number;
}

/** 审计动作分布点（REQ-20261119-011524 契约①，对齐 ActionCount；top N by action）。 */
export interface DashboardActionCount {
  action: string;
  count: number;
}

/** 平台概览聚合视图（REQ-20261119-011524 契约①，对齐 DashboardSummaryView）。
 *  wire Long→string 口径的数字字段已在 api 层容错解析为 number，下游统一消费数值；
 *  下游 Feign 降级时对应字段由后端给 0/空（认证即可，无新权限点）。 */
export interface DashboardSummaryView {
  users: number;
  roles: number;
  tenants: number;
  onlineUsers: number;
  unreadMessages: number;
  loginTrend: DashboardTrendPoint[];
  auditTopActions: DashboardActionCount[];
}

/** summary wire 形态（后端 Long→string；字段缺失/空对应下游降级 0/空口径）。 */
type DashboardSummaryWire = {
  users?: string | number | null;
  roles?: string | number | null;
  tenants?: string | number | null;
  onlineUsers?: string | number | null;
  unreadMessages?: string | number | null;
  loginTrend?: Array<{ date?: string | null; count?: string | number | null }> | null;
  auditTopActions?: Array<{ action?: string | null; count?: string | number | null }> | null;
};

/** wire 数字容错解析（Long→string 口径为主，number 直收兼容序列化口径演进；缺失/非法 → 0）。 */
const wireCount = (value: unknown): number => {
  if (typeof value === 'number') {
    return Number.isFinite(value) ? value : 0;
  }
  if (typeof value === 'string') {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : 0;
  }
  return 0;
};

/** wire → 视图解析（外部输入运行时校验，不做类型断言；无 data → null，调用方按降级展示）。 */
const parseDashboardSummary = (raw: DashboardSummaryWire | null): DashboardSummaryView | null => {
  if (!raw || typeof raw !== 'object') {
    return null;
  }
  return {
    users: wireCount(raw.users),
    roles: wireCount(raw.roles),
    tenants: wireCount(raw.tenants),
    onlineUsers: wireCount(raw.onlineUsers),
    unreadMessages: wireCount(raw.unreadMessages),
    loginTrend: (Array.isArray(raw.loginTrend) ? raw.loginTrend : []).map((point) => ({
      date: typeof point?.date === 'string' ? point.date : '',
      count: wireCount(point?.count),
    })),
    auditTopActions: (Array.isArray(raw.auditTopActions) ? raw.auditTopActions : []).map((point) => ({
      action: typeof point?.action === 'string' ? point.action : '',
      count: wireCount(point?.count),
    })),
  };
};

/** 推送令牌平台枚举（REQ-20261119-082531 契约⑤；sys_push_token.platform varchar(8)）。 */
export type PushTokenPlatform = 'ios' | 'android' | 'web';

/** 本人头像上传端点（REQ-20261119-082531，TL 裁决：落 platform-infra 认证面、免 infra:file:upload
 *  权限点；内部走 FileUseCase（bizType='avatar'），multipart file → FileView）。 */
export const ME_AVATAR_ENDPOINT = '/admin/v1/infra/me/avatar' as const;

/** 推送令牌端点（REQ-20261119-082531 契约⑤；POST upsert / DELETE 按 token query 删除）。 */
export const PUSH_TOKEN_ENDPOINT = '/admin/v1/system/me/push-tokens' as const;

/** 租户系统配置视图（契约对齐 AdminSystemConfigView，REQ-20261116-230354；version 乐观锁字符串）。
 *  REQ-20261117-011514 扩展 captchaEnabled（缺省视图=false）。
 *  REQ-20261117-025200 扩展页脚版权两字段（缺省视图 copyright=null/visible=false，未配置不显示）。
 *  REQ-20261117-180000 扩展水印模板（缺省视图 null=未配置→仅用户名）。 */
export interface AdminSystemConfigView {
  watermarkEnabled: boolean;
  /** 登录图形验证码开关（REQ-20261117-011514；无配置行缺省 false）。 */
  captchaEnabled: boolean;
  /** 闲置锁屏时长（毫秒），0 = 关闭；合法值 0/60000/300000/900000。 */
  lockIdleTimeoutMs: number;
  /** 页脚版权文案（REQ-20261117-025200；≤255，可空——null/空串均不渲染页脚）。 */
  footerCopyright: string | null;
  /** 页脚显隐开关（REQ-20261117-025200；无配置行缺省 false）。 */
  footerVisible: boolean;
  /** 水印内容模板（REQ-20261117-180000；≤255，可空——null/空串仅渲染用户名）。 */
  watermarkTemplate: string | null;
  version: string;
}

/** 租户系统配置更新载荷（契约对齐 AdminSystemConfigUpdate；version 乐观锁字符串）。 */
export interface AdminSystemConfigUpdate {
  watermarkEnabled: boolean;
  captchaEnabled: boolean;
  lockIdleTimeoutMs: number;
  /** 页脚版权文案（REQ-20261117-025200；≤255，可空）。 */
  footerCopyright: string | null;
  /** 页脚显隐开关（REQ-20261117-025200）。 */
  footerVisible: boolean;
  /** 水印内容模板（REQ-20261117-180000；≤255，可空；未知占位符后端拒绝）。 */
  watermarkTemplate: string | null;
  version: string;
}

/** 租户视图（契约对齐 AdminTenantView；id/version 序列化为字符串）。
 *  REQ-20261117-025200 扩展联系人/联系电话/有效期（可空；expiresAt ISO-8601 字符串，null=长期）。 */
export interface AdminTenantView {
  id: string;
  tenantCode: string;
  tenantName: string;
  status: 'ENABLED' | 'DISABLED';
  remark: string | null;
  contactName: string | null;
  contactPhone: string | null;
  /** 有效期（ISO-8601 字符串；null=长期；过期租户登录被拒，存量允许过去时间）。 */
  expiresAt: string | null;
  createdAt: string;
  version: string;
}

/** 角色视图（契约对齐 AdminRoleView；id/version/parentId 序列化为字符串或空）。
 *  REQ-20261114-120104 扩展 dataScope 列（角色-数据范围模型，五档枚举）。 */
export interface AdminRoleView {
  id: string;
  parentId: string | null;
  roleCode: string;
  roleName: string;
  status: 'ENABLED' | 'DISABLED';
  builtIn: boolean | null;
  remark: string | null;
  dataScope: RoleDataScope;
  version: string;
}

/** 权限点视图（契约对齐 AdminPermissionView；平台级目录全租户统一）。 */
export interface AdminPermissionView {
  id: string;
  permissionCode: string;
  permissionName: string;
  permissionType: string;
  httpMethod: string | null;
  httpPath: string | null;
  status: 'ENABLED' | 'DISABLED';
  version: string;
}

/** 部门视图（契约对齐 AdminDeptView；树形 children 嵌套，含禁用态；id/parentId/version 字符串）。 */
export interface AdminDeptView {
  id: string;
  parentId: string;
  deptName: string;
  sortOrder: number;
  status: 'ENABLED' | 'DISABLED';
  children: AdminDeptView[];
  version: string;
}

/** 岗位视图（契约对齐 AdminPositionView；id/version 字符串）。 */
export interface AdminPositionView {
  id: string;
  positionCode: string;
  positionName: string;
  status: 'ENABLED' | 'DISABLED';
  createdAt: string;
  version: string;
}

/** 角色数据范围枚举（REQ-20261114-120104 确认记录①五档模型；wire 值契约字面）。 */
export type RoleDataScope = 'ALL' | 'DEPT' | 'DEPT_AND_CHILD' | 'SELF' | 'CUSTOM';

/** 数据范围五档 wire 值全集（展示名经 i18n roles.scope.options 映射）。 */
export const DATA_SCOPE_VALUES: RoleDataScope[] = ['ALL', 'DEPT', 'DEPT_AND_CHILD', 'SELF', 'CUSTOM'];

/** 字典类型视图（契约对齐 AdminDictTypeView）。 */
export interface AdminDictTypeView {
  id: string;
  dictCode: string;
  dictName: string;
  status: 'ENABLED' | 'DISABLED';
  remark: string | null;
  version: string;
}

/** 字典条目视图（契约对齐 AdminDictItemView）。 */
export interface AdminDictItemView {
  id: string;
  dictTypeId: string;
  itemLabel: string;
  itemValue: string;
  sortOrder: number;
  status: 'ENABLED' | 'DISABLED';
  version: string;
}

/** 系统域接口（platform-system；P1 me + P2 租户/用户 + P3 角色/权限/菜单/字典管理域）。 */
export const systemApi = {
  /** 当前登录者视图（认证即可访问；会话恢复校验 + 身份/角色/权限/菜单引导）。 */
  me: (): Promise<AdminMeView> => apiFetch<AdminMeView>('/admin/v1/system/me'),

  // ---- 平台概览（REQ-20261119-011524；认证即可，无新权限点） ----
  /** 聚合统计（六数字 + 近14日登录趋势 + 审计动作分布 top5；wire 数字已在 api 层解析为 number）。
   *  后端下游 Feign 失败时对应字段降级 0/空；data 缺失（异常信封）返回 null 由调用方按降级展示。 */
  dashboardSummary: (): Promise<DashboardSummaryView | null> =>
    apiFetch<DashboardSummaryWire | null>('/admin/v1/system/dashboard/summary').then(parseDashboardSummary),

  // ---- 个人中心（REQ-20261119-082531；全部认证即可，无新权限点） ----
  /** 更新本人资料（契约①）：成功返回最新 AdminMeView（调用方据此刷新 meStore）。 */
  meUpdate: (payload: MeUpdatePayload): Promise<AdminMeView> =>
    apiFetch<AdminMeView>('/admin/v1/system/me', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),
  /** 上传本人头像（契约②+TL 裁决两步上传第一步）：multipart POST /admin/v1/infra/me/avatar
   *  → FileView(url)；url 由调用方经 meUpdate.avatarUrl 提交后才落 sys_user.avatar_url。
   *  旧头像文件不删（后端保留期任务自然清理）。 */
  meAvatarUpload: (file: File): Promise<FileView> => {
    const form = new FormData();
    form.append('file', file);
    return apiFetch<FileView>(ME_AVATAR_ENDPOINT, { method: 'POST', body: form });
  },
  /** 读取本人通知偏好（契约⑤；无偏好行返回缺省 messagePush=false）。 */
  mePreferences: (): Promise<UserPreferenceView> =>
    apiFetch<UserPreferenceView>('/admin/v1/system/me/preferences'),
  /** 更新本人通知偏好（契约⑤；本人行 upsert，字段集 {messagePush}）。 */
  mePreferencesUpdate: (payload: { messagePush: boolean }): Promise<UserPreferenceView> =>
    apiFetch<UserPreferenceView>('/admin/v1/system/me/preferences', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),
  /** 注册推送令牌（契约⑤；本人 token upsert——同 token 幂等；void 端点兼容空体）。 */
  pushTokenRegister: (payload: { token: string; platform: PushTokenPlatform }): Promise<void> =>
    apiFetch<void>(PUSH_TOKEN_ENDPOINT, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }, { emptyAsSuccess: true }),
  /** 移除推送令牌（契约⑤；DELETE ?token= 删本人行；幂等成功）。 */
  pushTokenRemove: (token: string): Promise<void> =>
    apiFetch<void>(`${PUSH_TOKEN_ENDPOINT}?token=${encodeURIComponent(token)}`, { method: 'DELETE' }, { emptyAsSuccess: true }),

  // ---- 租户系统配置（REQ-20261116-230354；GET 认证即可，PUT 需 system:config:update） ----
  /** 读取当前租户配置（无配置行返回缺省 true/false/0/"0"，REQ-20261117-011514 captchaEnabled=false）。 */
  configGet: (): Promise<AdminSystemConfigView> => apiFetch<AdminSystemConfigView>('/admin/v1/system/config'),
  /** 更新当前租户配置（upsert；version 乐观锁，成功 version+1）。 */
  configUpdate: (payload: AdminSystemConfigUpdate): Promise<AdminSystemConfigView> =>
    apiFetch<AdminSystemConfigView>('/admin/v1/system/config', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),

  // ---- 租户管理（TENANT_* 权限码） ----
  /** 租户分页（keyword 匹配代码/名称；signal 为可选取消信号，透传 apiFetch）。 */
  tenantList: (page: number, size: number, keyword?: string, signal?: AbortSignal): Promise<PageResult<AdminTenantView>> =>
    apiFetch<PageResult<AdminTenantView>>(`/admin/v1/system/tenants?page=${page}&size=${size}${keyword ? `&keyword=${encodeURIComponent(keyword)}` : ''}`, { signal }),
  /** 新建租户（tenantCode 全局唯一且不可改；REQ-20261117-025200 增联系人/联系电话/有效期，可空=长期）。 */
  tenantCreate: (payload: { tenantCode: string; tenantName: string; remark?: string; contactName?: string | null; contactPhone?: string | null; expiresAt?: string | null }): Promise<AdminTenantView> =>
    apiFetch<AdminTenantView>('/admin/v1/system/tenants', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),
  /** 租户改名/备注/联系人/有效期（version 乐观锁）。 */
  tenantUpdate: (tenantId: string, payload: { tenantName: string; remark?: string; contactName?: string | null; contactPhone?: string | null; expiresAt?: string | null; version: string }): Promise<AdminTenantView> =>
    apiFetch<AdminTenantView>(`/admin/v1/system/tenants/${tenantId}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),
  /** 停用/启用租户（停用即"删除"，FR-007）。 */
  tenantStatus: (tenantId: string, status: 'ENABLED' | 'DISABLED'): Promise<void> =>
    apiFetch<void>(`/admin/v1/system/tenants/${tenantId}/status`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ status }),
    }),

  // ---- 用户管理（USER_* 权限码） ----
  /** 用户分页（username 匹配、status/deptId 过滤——deptId 含子部门递归为后端语义；signal 为可选取消信号，透传 apiFetch）。 */
  userList: (page: number, size: number, username?: string, status?: 'ENABLED' | 'DISABLED', deptId?: string | null, signal?: AbortSignal): Promise<PageResult<AdminUserView>> =>
    apiFetch<PageResult<AdminUserView>>(`/admin/v1/system/users?page=${page}&size=${size}${username ? `&username=${encodeURIComponent(username)}` : ''}${status ? `&status=${status}` : ''}${deptId ? `&deptId=${encodeURIComponent(deptId)}` : ''}`, { signal }),
  /** 新建用户（首密码经管理端 credentials 接口另行重置；deptId/positionId 可空=未分配）。 */
  userCreate: (payload: { username: string; nickname: string; email?: string; phone?: string; roleIds: string[]; deptId?: string | null; positionId?: string | null }): Promise<AdminUserView> =>
    apiFetch<AdminUserView>('/admin/v1/system/users', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        ...payload,
        roleIds: payload.roleIds.map(Number),
        deptId: payload.deptId ? Number(payload.deptId) : null,
        positionId: payload.positionId ? Number(payload.positionId) : null,
      }),
    }),
  /** 用户改名/邮箱/手机/部门/岗位（version 乐观锁）。 */
  userUpdate: (userId: string, payload: { nickname: string; email?: string; phone?: string; deptId?: string | null; positionId?: string | null; version: string }): Promise<AdminUserView> =>
    apiFetch<AdminUserView>(`/admin/v1/system/users/${userId}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        ...payload,
        deptId: payload.deptId ? Number(payload.deptId) : null,
        positionId: payload.positionId ? Number(payload.positionId) : null,
      }),
    }),
  /** 删除用户（builtIn 内置账号由后端拒绝）。 */
  userDelete: (userId: string): Promise<void> =>
    apiFetch<void>(`/admin/v1/system/users/${userId}`, { method: 'DELETE' }),
  /** 启用/停用用户。 */
  userStatus: (userId: string, status: 'ENABLED' | 'DISABLED'): Promise<void> =>
    apiFetch<void>(`/admin/v1/system/users/${userId}/status`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ status }),
    }),
  /** 绑定角色（全量覆盖语义）。 */
  userBindRoles: (userId: string, roleIds: string[]): Promise<void> =>
    apiFetch<void>(`/admin/v1/system/users/${userId}/roles`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ roleIds: roleIds.map(Number) }),
    }),

  // ---- 角色管理（ROLE_* / ROLE_GRANT 权限码，P3） ----
  /** 角色分页（keyword 匹配代码/名称；signal 为可选取消信号，透传 apiFetch）。 */
  roleList: (page = 1, size = 100, keyword?: string, signal?: AbortSignal): Promise<PageResult<AdminRoleView>> =>
    apiFetch<PageResult<AdminRoleView>>(`/admin/v1/system/roles?page=${page}&size=${size}${keyword ? `&keyword=${encodeURIComponent(keyword)}` : ''}`, { signal }),
  /** 新建角色（roleCode 模式与租户代码同型；parentId 空为顶级）。 */
  roleCreate: (payload: { parentId?: number | null; roleCode: string; roleName: string; remark?: string }): Promise<AdminRoleView> =>
    apiFetch<AdminRoleView>('/admin/v1/system/roles', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...payload, parentId: payload.parentId ?? null }),
    }),
  /** 角色改名/父级/备注（version 乐观锁）。 */
  roleUpdate: (roleId: string, payload: { roleName: string; remark?: string; parentId?: number | null; version: string }): Promise<AdminRoleView> =>
    apiFetch<AdminRoleView>(`/admin/v1/system/roles/${roleId}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...payload, parentId: payload.parentId ?? null }),
    }),
  /** 启用/停用角色。 */
  roleStatus: (roleId: string, status: 'ENABLED' | 'DISABLED'): Promise<void> =>
    apiFetch<void>(`/admin/v1/system/roles/${roleId}/status`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ status }),
    }),
  /** 删除角色（被引用时由后端拒绝）。 */
  roleDelete: (roleId: string): Promise<void> =>
    apiFetch<void>(`/admin/v1/system/roles/${roleId}`, { method: 'DELETE' }),
  /** 角色已授权限点全量清单（授权抽屉勾选数据源）。 */
  rolePermissions: (roleId: string): Promise<AdminPermissionView[]> =>
    apiFetch<AdminPermissionView[]>(`/admin/v1/system/roles/${roleId}/permissions`),
  /** 绑定权限点（全量覆盖语义）。 */
  roleBindPermissions: (roleId: string, permissionIds: string[]): Promise<void> =>
    apiFetch<void>(`/admin/v1/system/roles/${roleId}/permissions`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ permissionIds: permissionIds.map(Number) }),
    }),
  /** 角色已绑菜单全量清单（树形）。 */
  roleMenus: (roleId: string): Promise<AdminMenuView[]> =>
    apiFetch<AdminMenuView[]>(`/admin/v1/system/roles/${roleId}/menus`),
  /** 绑定菜单（全量覆盖语义）。 */
  roleBindMenus: (roleId: string, menuIds: number[]): Promise<void> =>
    apiFetch<void>(`/admin/v1/system/roles/${roleId}/menus`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ menuIds }),
    }),
  /** 设置角色数据范围（REQ-20261114-120104；CUSTOM 时 deptIds 必传非空，由后端校验 DATA_SCOPE_INVALID）。 */
  roleDataScope: (roleId: string, dataScope: RoleDataScope, deptIds: string[]): Promise<AdminRoleView> =>
    apiFetch<AdminRoleView>(`/admin/v1/system/roles/${roleId}/data-scope`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ dataScope, deptIds: deptIds.map(Number) }),
    }),

  // ---- 权限点管理（PERMISSION_* 权限码，P3） ----
  /** 权限点分页（平台级目录；signal 为可选取消信号，透传 apiFetch）。 */
  permissionList: (page = 1, size = 50, signal?: AbortSignal): Promise<PageResult<AdminPermissionView>> =>
    apiFetch<PageResult<AdminPermissionView>>(`/admin/v1/system/permissions?page=${page}&size=${size}`, { signal }),
  /** 新建权限点（permissionCode 模式 ^[a-z][a-z0-9:-]*$）。 */
  permissionCreate: (payload: { permissionCode: string; permissionName: string; permissionType: string; httpMethod?: string; httpPath?: string }): Promise<AdminPermissionView> =>
    apiFetch<AdminPermissionView>('/admin/v1/system/permissions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),
  /** 权限点改名/方法/路径（version 乐观锁；code 不可改）。 */
  permissionUpdate: (permissionId: string, payload: { permissionName: string; httpMethod?: string; httpPath?: string; version: string }): Promise<AdminPermissionView> =>
    apiFetch<AdminPermissionView>(`/admin/v1/system/permissions/${permissionId}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),
  /** 删除权限点。 */
  permissionDelete: (permissionId: string): Promise<void> =>
    apiFetch<void>(`/admin/v1/system/permissions/${permissionId}`, { method: 'DELETE' }),

  // ---- 菜单管理（MENU_* 权限码，P3） ----
  /** 菜单树（全量；前端渲染树形表格）。page/size 为可选分页参数：全量树不截断，
   *  仅用于统一列表请求携带分页口径（后端按全量返回）；signal 为可选取消信号，透传 apiFetch。 */
  menuTree: (page?: number, size?: number, signal?: AbortSignal): Promise<AdminMenuView[]> => {
    const qs = new URLSearchParams();
    if (page !== undefined) qs.set('page', String(page));
    if (size !== undefined) qs.set('size', String(size));
    const suffix = page !== undefined || size !== undefined ? `?${qs.toString()}` : '';
    return apiFetch<AdminMenuView[]>(`/admin/v1/system/menus${suffix}`, { signal });
  },
  /** 新建菜单（parentId 空=根；menuType DIRECTORY|MENU）。 */
  menuCreate: (payload: { parentId?: number | null; menuName: string; menuType: string; path?: string; component?: string; icon?: string; sortOrder: number; visible?: boolean; permissionCode?: string }): Promise<AdminMenuView> =>
    apiFetch<AdminMenuView>('/admin/v1/system/menus', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...payload, parentId: payload.parentId ?? null }),
    }),
  /** 菜单改名/形态/排序/可见性（version 乐观锁）。 */
  menuUpdate: (menuId: number, payload: { menuName: string; path?: string; component?: string; icon?: string; sortOrder: number; visible?: boolean; permissionCode?: string; version: string }): Promise<AdminMenuView> =>
    apiFetch<AdminMenuView>(`/admin/v1/system/menus/${menuId}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),
  /** 删除菜单。 */
  menuDelete: (menuId: number): Promise<void> =>
    apiFetch<void>(`/admin/v1/system/menus/${menuId}`, { method: 'DELETE' }),

  // ---- 部门管理（DEPT_* 权限码，REQ-20261114-120104） ----
  /** 部门树（全量、含禁用态；前端渲染树形表格/选择器）。signal 为可选取消信号，透传 apiFetch。 */
  deptTree: (signal?: AbortSignal): Promise<AdminDeptView[]> =>
    apiFetch<AdminDeptView[]>('/admin/v1/system/depts', { signal }),
  /** 新建部门（parentId 空=根部门；sortOrder/status 随体提交）。 */
  deptCreate: (payload: { parentId?: number | null; deptName: string; sortOrder: number; status?: 'ENABLED' | 'DISABLED' }): Promise<AdminDeptView> =>
    apiFetch<AdminDeptView>('/admin/v1/system/depts', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...payload, parentId: payload.parentId ?? null }),
    }),
  /** 部门改名/父级/排序/状态（version 乐观锁）。 */
  deptUpdate: (deptId: string, payload: { parentId?: number | null; deptName: string; sortOrder: number; status?: 'ENABLED' | 'DISABLED'; version: string }): Promise<AdminDeptView> =>
    apiFetch<AdminDeptView>(`/admin/v1/system/depts/${deptId}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...payload, parentId: payload.parentId ?? null }),
    }),
  /** 删除部门（有子部门/有用户由后端拒绝：DEPT_HAS_CHILDREN/DEPT_HAS_USERS）。 */
  deptDelete: (deptId: string): Promise<void> =>
    apiFetch<void>(`/admin/v1/system/depts/${deptId}`, { method: 'DELETE' }),

  // ---- 岗位管理（POSITION_* 权限码，REQ-20261114-120104） ----
  /** 岗位分页（keyword 匹配名称/编码；signal 为可选取消信号，透传 apiFetch）。 */
  positionList: (page: number, size: number, keyword?: string, signal?: AbortSignal): Promise<PageResult<AdminPositionView>> =>
    apiFetch<PageResult<AdminPositionView>>(`/admin/v1/system/positions?page=${page}&size=${size}${keyword ? `&keyword=${encodeURIComponent(keyword)}` : ''}`, { signal }),
  /** 新建岗位（positionCode 全局唯一且不可改）。 */
  positionCreate: (payload: { positionCode: string; positionName: string; status?: 'ENABLED' | 'DISABLED' }): Promise<AdminPositionView> =>
    apiFetch<AdminPositionView>('/admin/v1/system/positions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),
  /** 岗位改名/编码/状态（version 乐观锁）。 */
  positionUpdate: (positionId: string, payload: { positionCode: string; positionName: string; status?: 'ENABLED' | 'DISABLED'; version: string }): Promise<AdminPositionView> =>
    apiFetch<AdminPositionView>(`/admin/v1/system/positions/${positionId}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),
  /** 删除岗位（被用户引用由后端拒绝：POSITION_IN_USE）。 */
  positionDelete: (positionId: string): Promise<void> =>
    apiFetch<void>(`/admin/v1/system/positions/${positionId}`, { method: 'DELETE' }),

  // ---- 字典管理（DICT_* 权限码，P3） ----
  /** 字典类型分页（signal 为可选取消信号，透传 apiFetch）。 */
  dictTypeList: (page = 1, size = 50, keyword?: string, signal?: AbortSignal): Promise<PageResult<AdminDictTypeView>> =>
    apiFetch<PageResult<AdminDictTypeView>>(`/admin/v1/system/dict-types?page=${page}&size=${size}${keyword ? `&keyword=${encodeURIComponent(keyword)}` : ''}`, { signal }),
  /** 新建字典类型。 */
  dictTypeCreate: (payload: { dictCode: string; dictName: string; remark?: string }): Promise<AdminDictTypeView> =>
    apiFetch<AdminDictTypeView>('/admin/v1/system/dict-types', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),
  /** 字典类型改名/备注（version 乐观锁）。 */
  dictTypeUpdate: (typeId: string, payload: { dictName: string; remark?: string; version: string }): Promise<AdminDictTypeView> =>
    apiFetch<AdminDictTypeView>(`/admin/v1/system/dict-types/${typeId}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),
  /** 删除字典类型（含条目时由后端拒绝）。 */
  dictTypeDelete: (typeId: string): Promise<void> =>
    apiFetch<void>(`/admin/v1/system/dict-types/${typeId}`, { method: 'DELETE' }),
  /** 字典条目清单（按类型）。page/size 可选：useDict 消费时传大页取全量（确认记录⑥）；
   *  缺省不携带查询参数（既有管理端调用不变）；signal 为可选取消信号，透传 apiFetch。 */
  dictItemList: (typeId: string, page?: number, size?: number, signal?: AbortSignal): Promise<AdminDictItemView[]> => {
    const qs = new URLSearchParams();
    if (page !== undefined) qs.set('page', String(page));
    if (size !== undefined) qs.set('size', String(size));
    const suffix = page !== undefined || size !== undefined ? `?${qs.toString()}` : '';
    return apiFetch<AdminDictItemView[]>(`/admin/v1/system/dict-types/${typeId}/items${suffix}`, { signal });
  },
  /** 新建字典条目。 */
  dictItemCreate: (typeId: string, payload: { itemLabel: string; itemValue: string; sortOrder: number }): Promise<AdminDictItemView> =>
    apiFetch<AdminDictItemView>(`/admin/v1/system/dict-types/${typeId}/items`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),
  /** 字典条目改标签/值/排序（version 乐观锁）。 */
  dictItemUpdate: (itemId: string, payload: { itemLabel: string; itemValue: string; sortOrder: number; version: string }): Promise<AdminDictItemView> =>
    apiFetch<AdminDictItemView>(`/admin/v1/system/dict-items/${itemId}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),
  /** 删除字典条目。 */
  dictItemDelete: (itemId: string): Promise<void> =>
    apiFetch<void>(`/admin/v1/system/dict-items/${itemId}`, { method: 'DELETE' }),
};
