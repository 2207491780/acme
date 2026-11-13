import { apiFetch } from './client';

/** 分页信封（契约对齐 PageResult；total 为 Long——按数字容错解析）。 */
export interface PageResult<T> {
  records: T[];
  total: number;
}

/** 用户视图（契约对齐 AdminUserView；id/version/roleIds 序列化为字符串）。 */
export interface AdminUserView {
  id: string;
  username: string;
  nickname: string | null;
  email: string | null;
  phone: string | null;
  status: 'ENABLED' | 'DISABLED';
  builtIn: boolean;
  roleIds: string[];
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

/** 当前登录者视图（契约对齐 AdminMeView；FR-008 权限声明与菜单可见性依据）。 */
export interface AdminMeView {
  user: AdminUserView;
  roles: string[];
  permissions: string[];
  menus: AdminMenuView[];
}

/** 系统域接口（platform-system；P1 me + P2 租户/用户/角色管理域打通）。 */
export const systemApi = {
  /** 当前登录者视图（认证即可访问；会话恢复校验 + 身份/角色/权限/菜单引导）。 */
  me: (): Promise<AdminMeView> => apiFetch<AdminMeView>('/admin/v1/system/me'),

  // ---- 租户管理（TENANT_* 权限码） ----
  /** 租户分页（keyword 匹配代码/名称）。 */
  tenantList: (page: number, size: number, keyword?: string): Promise<PageResult<AdminTenantView>> =>
    apiFetch<PageResult<AdminTenantView>>(`/admin/v1/system/tenants?page=${page}&size=${size}${keyword ? `&keyword=${encodeURIComponent(keyword)}` : ''}`),
  /** 新建租户（tenantCode 全局唯一且不可改）。 */
  tenantCreate: (payload: { tenantCode: string; tenantName: string; remark?: string }): Promise<AdminTenantView> =>
    apiFetch<AdminTenantView>('/admin/v1/system/tenants', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),
  /** 租户改名/备注（version 乐观锁）。 */
  tenantUpdate: (tenantId: string, payload: { tenantName: string; remark?: string; version: string }): Promise<AdminTenantView> =>
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
  /** 用户分页（username 匹配、status 过滤）。 */
  userList: (page: number, size: number, username?: string, status?: 'ENABLED' | 'DISABLED'): Promise<PageResult<AdminUserView>> =>
    apiFetch<PageResult<AdminUserView>>(`/admin/v1/system/users?page=${page}&size=${size}${username ? `&username=${encodeURIComponent(username)}` : ''}${status ? `&status=${status}` : ''}`),
  /** 新建用户（首密码经管理端 credentials 接口另行重置）。 */
  userCreate: (payload: { username: string; nickname: string; email?: string; phone?: string; roleIds: string[] }): Promise<AdminUserView> =>
    apiFetch<AdminUserView>('/admin/v1/system/users', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...payload, roleIds: payload.roleIds.map(Number) }),
    }),
  /** 用户改名/邮箱/手机（version 乐观锁）。 */
  userUpdate: (userId: string, payload: { nickname: string; email?: string; phone?: string; version: string }): Promise<AdminUserView> =>
    apiFetch<AdminUserView>(`/admin/v1/system/users/${userId}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
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

  // ---- 角色目录（USER_ASSIGN_ROLE / ROLE_LIST 依赖） ----
  /** 角色分页（取首页大页供角色选择器）。 */
  roleList: (page = 1, size = 100): Promise<PageResult<AdminRoleView>> =>
    apiFetch<PageResult<AdminRoleView>>(`/admin/v1/system/roles?page=${page}&size=${size}`),
};

/** 租户视图（契约对齐 AdminTenantView；id/version 序列化为字符串）。 */
export interface AdminTenantView {
  id: string;
  tenantCode: string;
  tenantName: string;
  status: 'ENABLED' | 'DISABLED';
  remark: string | null;
  createdAt: string;
  version: string;
}

/** 角色视图（选择器用摘要；契约对齐 AdminRoleView）。 */
export interface AdminRoleView {
  id: string;
  roleCode: string;
  roleName: string;
  status: 'ENABLED' | 'DISABLED';
  builtIn: boolean | null;
}
