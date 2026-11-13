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

/** 角色视图（契约对齐 AdminRoleView；id/version/parentId 序列化为字符串或空）。 */
export interface AdminRoleView {
  id: string;
  parentId: string | null;
  roleCode: string;
  roleName: string;
  status: 'ENABLED' | 'DISABLED';
  builtIn: boolean | null;
  remark: string | null;
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

  // ---- 角色管理（ROLE_* / ROLE_GRANT 权限码，P3） ----
  /** 角色分页（keyword 匹配代码/名称）。 */
  roleList: (page = 1, size = 100, keyword?: string): Promise<PageResult<AdminRoleView>> =>
    apiFetch<PageResult<AdminRoleView>>(`/admin/v1/system/roles?page=${page}&size=${size}${keyword ? `&keyword=${encodeURIComponent(keyword)}` : ''}`),
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

  // ---- 权限点管理（PERMISSION_* 权限码，P3） ----
  /** 权限点分页（平台级目录）。 */
  permissionList: (page = 1, size = 50): Promise<PageResult<AdminPermissionView>> =>
    apiFetch<PageResult<AdminPermissionView>>(`/admin/v1/system/permissions?page=${page}&size=${size}`),
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
  /** 菜单树（全量；前端渲染树形表格）。 */
  menuTree: (): Promise<AdminMenuView[]> =>
    apiFetch<AdminMenuView[]>('/admin/v1/system/menus'),
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

  // ---- 字典管理（DICT_* 权限码，P3） ----
  /** 字典类型分页。 */
  dictTypeList: (page = 1, size = 50, keyword?: string): Promise<PageResult<AdminDictTypeView>> =>
    apiFetch<PageResult<AdminDictTypeView>>(`/admin/v1/system/dict-types?page=${page}&size=${size}${keyword ? `&keyword=${encodeURIComponent(keyword)}` : ''}`),
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
  /** 字典条目全量清单（按类型）。 */
  dictItemList: (typeId: string): Promise<AdminDictItemView[]> =>
    apiFetch<AdminDictItemView[]>(`/admin/v1/system/dict-types/${typeId}/items`),
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
