import { http } from 'msw';
import type { AdminDictTypeView, AdminMenuView, AdminRoleView, AdminTenantView, AdminUserView } from '@acme/core';
import * as db from '../data';
import { bodyOf, empty, fail, ok, page, pagingOf, slicePage, urlOf } from '../utils';

/** 系统域 handler（platform-system）：me / 租户 / 用户 / 角色 / 权限点 / 菜单 / 字典。
 *  resolver 内联（msw 2.x Params 泛型按路径逐 handler 推断，不抽公共别名）。 */

/** 通用 keyword 过滤（命中任一字段子串，大小写不敏感）。 */
function matchKeyword<T>(rows: T[], keyword: string | null, fields: (row: T) => (string | null | undefined)[]): T[] {
  if (!keyword) {
    return rows;
  }
  const needle = keyword.toLowerCase();
  return rows.filter((row) => fields(row).some((value) => value?.toLowerCase().includes(needle)));
}

const keywordOf = (request: Request): string | null => urlOf(request).searchParams.get('keyword');

/** 审计可写集合：dev mock 的内存态允许增删改（仅内存，不落盘）。 */
const store = {
  tenants: [...db.tenants],
  users: [...db.users],
  roles: [...db.roles],
  permissions: [...db.permissions],
  menus: [...db.menus],
  dictTypes: [...db.dictTypes],
  dictItems: [...db.dictItems],
};

let nextId = 10000;
const genId = (): string => String(nextId++);

const nowIso = (): string => new Date().toISOString();

export const systemHandlers = [
  // ---- me ----
  http.get('/admin/v1/system/me', () => ok({ ...db.me, menus: store.menus })),

  // ---- 租户 ----
  http.get('/admin/v1/system/tenants', ({ request }) => {
    const rows = matchKeyword(store.tenants, keywordOf(request), (tenant: AdminTenantView) => [tenant.tenantCode, tenant.tenantName]);
    return page(slicePage(rows, pagingOf(request)), rows.length);
  }),
  http.post('/admin/v1/system/tenants', async ({ request }) => {
    const body = await bodyOf(request);
    if (typeof body.tenantCode !== 'string' || typeof body.tenantName !== 'string') {
      return fail('TENANT_CODE_INVALID', '租户代码/名称缺失', 400);
    }
    const created = {
      id: genId(), tenantCode: body.tenantCode, tenantName: body.tenantName,
      status: 'ENABLED' as const, remark: (body.remark as string | undefined) ?? null,
      createdAt: nowIso(), version: '0',
    };
    store.tenants = [created, ...store.tenants];
    return ok(created);
  }),
  http.put('/admin/v1/system/tenants/:tenantId', async ({ request, params }) => {
    const body = await bodyOf(request);
    const id = String(params.tenantId);
    const target = store.tenants.find((tenant) => tenant.id === id);
    if (!target) {
      return fail('TENANT_NOT_FOUND', '租户不存在或已被删除', 404);
    }
    const updated = {
      ...target,
      tenantName: typeof body.tenantName === 'string' ? body.tenantName : target.tenantName,
      remark: (body.remark as string | null) ?? target.remark,
      version: String(Number(target.version) + 1),
    };
    store.tenants = store.tenants.map((tenant) => (tenant.id === id ? updated : tenant));
    return ok(updated);
  }),
  http.put('/admin/v1/system/tenants/:tenantId/status', async ({ request, params }) => {
    const body = await bodyOf(request);
    const id = String(params.tenantId);
    store.tenants = store.tenants.map((tenant) => (tenant.id === id ? { ...tenant, status: body.status === 'DISABLED' ? 'DISABLED' : 'ENABLED' } : tenant));
    return empty();
  }),

  // ---- 用户 ----
  http.get('/admin/v1/system/users', ({ request }) => {
    const params = urlOf(request).searchParams;
    const status = params.get('status');
    const rows = matchKeyword(store.users, params.get('username'), (u: AdminUserView) => [u.username])
      .filter((u) => !status || u.status === status);
    return page(slicePage(rows, pagingOf(request)), rows.length);
  }),
  http.post('/admin/v1/system/users', async ({ request }) => {
    const body = await bodyOf(request);
    const roleIds = Array.isArray(body.roleIds) ? (body.roleIds as unknown[]).map(String) : [];
    const created = {
      id: genId(),
      username: String(body.username ?? 'user'),
      nickname: String(body.nickname ?? ''),
      email: (body.email as string | undefined) ?? null,
      phone: (body.phone as string | undefined) ?? null,
      status: 'ENABLED' as const,
      builtIn: false,
      roleIds,
      createdAt: nowIso(),
      updatedAt: nowIso(),
      version: '0',
    };
    store.users = [created, ...store.users];
    return ok(created);
  }),
  http.put('/admin/v1/system/users/:userId', async ({ request, params }) => {
    const body = await bodyOf(request);
    const id = String(params.userId);
    const target = store.users.find((u) => u.id === id);
    if (!target) {
      return fail('USER_NOT_FOUND', '用户不存在或已被删除', 404);
    }
    const updated = {
      ...target,
      nickname: typeof body.nickname === 'string' ? body.nickname : target.nickname,
      email: (body.email as string | null) ?? target.email,
      phone: (body.phone as string | null) ?? target.phone,
      updatedAt: nowIso(),
      version: String(Number(target.version) + 1),
    };
    store.users = store.users.map((u) => (u.id === id ? updated : u));
    return ok(updated);
  }),
  http.delete('/admin/v1/system/users/:userId', ({ params }) => {
    const id = String(params.userId);
    store.users = store.users.filter((u) => u.id !== id);
    return empty();
  }),
  http.put('/admin/v1/system/users/:userId/status', async ({ request, params }) => {
    const body = await bodyOf(request);
    const id = String(params.userId);
    store.users = store.users.map((u) => (u.id === id ? { ...u, status: body.status === 'DISABLED' ? 'DISABLED' : 'ENABLED' } : u));
    return empty();
  }),
  http.put('/admin/v1/system/users/:userId/roles', async ({ request, params }) => {
    const body = await bodyOf(request);
    const id = String(params.userId);
    const roleIds = Array.isArray(body.roleIds) ? (body.roleIds as unknown[]).map(String) : [];
    store.users = store.users.map((u) => (u.id === id ? { ...u, roleIds } : u));
    return empty();
  }),

  // ---- 角色 ----
  http.get('/admin/v1/system/roles', ({ request }) => {
    const rows = matchKeyword(store.roles, keywordOf(request), (role: AdminRoleView) => [role.roleCode, role.roleName]);
    return page(slicePage(rows, pagingOf(request)), rows.length);
  }),
  http.post('/admin/v1/system/roles', async ({ request }) => {
    const body = await bodyOf(request);
    const created = {
      id: genId(),
      parentId: body.parentId ? String(body.parentId) : null,
      roleCode: String(body.roleCode ?? 'role'),
      roleName: String(body.roleName ?? ''),
      status: 'ENABLED' as const,
      builtIn: false,
      remark: (body.remark as string | undefined) ?? null,
      version: '0',
    };
    store.roles = [created, ...store.roles];
    return ok(created);
  }),
  http.put('/admin/v1/system/roles/:roleId', async ({ request, params }) => {
    const body = await bodyOf(request);
    const id = String(params.roleId);
    const target = store.roles.find((role) => role.id === id);
    if (!target) {
      return fail('ROLE_NOT_FOUND', '角色不存在或已被删除', 404);
    }
    const updated = {
      ...target,
      roleName: typeof body.roleName === 'string' ? body.roleName : target.roleName,
      remark: (body.remark as string | null) ?? target.remark,
      version: String(Number(target.version) + 1),
    };
    store.roles = store.roles.map((role) => (role.id === id ? updated : role));
    return ok(updated);
  }),
  http.put('/admin/v1/system/roles/:roleId/status', async ({ request, params }) => {
    const body = await bodyOf(request);
    const id = String(params.roleId);
    store.roles = store.roles.map((role) => (role.id === id ? { ...role, status: body.status === 'DISABLED' ? 'DISABLED' : 'ENABLED' } : role));
    return empty();
  }),
  http.delete('/admin/v1/system/roles/:roleId', ({ params }) => {
    const id = String(params.roleId);
    store.roles = store.roles.filter((role) => role.id !== id);
    return empty();
  }),
  http.get('/admin/v1/system/roles/:roleId/permissions', ({ params }) => ok(String(params.roleId) === '1' ? store.permissions : [])),
  http.put('/admin/v1/system/roles/:roleId/permissions', () => empty()),
  http.get('/admin/v1/system/roles/:roleId/menus', ({ params }) => ok(String(params.roleId) === '1' ? store.menus : [])),
  http.put('/admin/v1/system/roles/:roleId/menus', () => empty()),

  // ---- 权限点 ----
  http.get('/admin/v1/system/permissions', ({ request }) => {
    const rows = store.permissions;
    return page(slicePage(rows, pagingOf(request)), rows.length);
  }),
  http.post('/admin/v1/system/permissions', async ({ request }) => {
    const body = await bodyOf(request);
    const created = {
      id: genId(),
      permissionCode: String(body.permissionCode ?? 'system:mock'),
      permissionName: String(body.permissionName ?? ''),
      permissionType: String(body.permissionType ?? 'API'),
      httpMethod: (body.httpMethod as string | undefined) ?? null,
      httpPath: (body.httpPath as string | undefined) ?? null,
      status: 'ENABLED' as const,
      version: '0',
    };
    store.permissions = [...store.permissions, created];
    return ok(created);
  }),
  http.put('/admin/v1/system/permissions/:permissionId', async ({ request, params }) => {
    const body = await bodyOf(request);
    const id = String(params.permissionId);
    const target = store.permissions.find((p) => p.id === id);
    if (!target) {
      return fail('PERMISSION_NOT_FOUND', '权限点不存在或已被删除', 404);
    }
    const updated = {
      ...target,
      permissionName: typeof body.permissionName === 'string' ? body.permissionName : target.permissionName,
      httpMethod: (body.httpMethod as string | null) ?? target.httpMethod,
      httpPath: (body.httpPath as string | null) ?? target.httpPath,
      version: String(Number(target.version) + 1),
    };
    store.permissions = store.permissions.map((p) => (p.id === id ? updated : p));
    return ok(updated);
  }),
  http.delete('/admin/v1/system/permissions/:permissionId', ({ params }) => {
    const id = String(params.permissionId);
    store.permissions = store.permissions.filter((p) => p.id !== id);
    return empty();
  }),

  // ---- 菜单 ----
  http.get('/admin/v1/system/menus', () => ok(store.menus)),
  http.post('/admin/v1/system/menus', async ({ request }) => {
    const body = await bodyOf(request);
    const created = {
      id: Number(genId()),
      parentId: body.parentId ? Number(body.parentId) : 0,
      menuName: String(body.menuName ?? '新菜单'),
      menuType: String(body.menuType ?? 'MENU'),
      path: (body.path as string | undefined) ?? null,
      component: (body.component as string | undefined) ?? null,
      icon: (body.icon as string | undefined) ?? null,
      sortOrder: Number(body.sortOrder ?? 0),
      visible: body.visible !== false,
      permissionCode: (body.permissionCode as string | undefined) ?? null,
      status: 'ENABLED' as const,
      version: '0',
      children: [] as AdminMenuView[],
    };
    store.menus = [...store.menus, created];
    return ok(created);
  }),
  http.put('/admin/v1/system/menus/:menuId', async ({ request, params }) => {
    const body = await bodyOf(request);
    const id = Number(params.menuId);
    let updated: AdminMenuView | null = null;
    const walk = (nodes: AdminMenuView[]): AdminMenuView[] =>
      nodes.map((node) => {
        if (node.id !== id) {
          return { ...node, children: walk(node.children) };
        }
        updated = {
          ...node,
          menuName: typeof body.menuName === 'string' ? body.menuName : node.menuName,
          path: (body.path as string | null) ?? node.path,
          component: (body.component as string | null) ?? node.component,
          icon: (body.icon as string | null) ?? node.icon,
          sortOrder: Number(body.sortOrder ?? node.sortOrder ?? 0),
          visible: body.visible !== undefined ? body.visible !== false : node.visible,
          permissionCode: (body.permissionCode as string | null) ?? node.permissionCode,
          version: String(Number(node.version) + 1),
        };
        return updated;
      });
    store.menus = walk(store.menus);
    return updated ? ok(updated) : fail('MENU_NOT_FOUND', '菜单不存在或已被删除', 404);
  }),
  http.delete('/admin/v1/system/menus/:menuId', ({ params }) => {
    const id = Number(params.menuId);
    const walk = (nodes: AdminMenuView[]): AdminMenuView[] =>
      nodes.filter((node) => node.id !== id).map((node) => ({ ...node, children: walk(node.children) }));
    store.menus = walk(store.menus);
    return empty();
  }),

  // ---- 字典 ----
  http.get('/admin/v1/system/dict-types', ({ request }) => {
    const rows = matchKeyword(store.dictTypes, keywordOf(request), (t: AdminDictTypeView) => [t.dictCode, t.dictName]);
    return page(slicePage(rows, pagingOf(request)), rows.length);
  }),
  http.post('/admin/v1/system/dict-types', async ({ request }) => {
    const body = await bodyOf(request);
    const created = {
      id: genId(),
      dictCode: String(body.dictCode ?? 'mock_dict'),
      dictName: String(body.dictName ?? ''),
      status: 'ENABLED' as const,
      remark: (body.remark as string | undefined) ?? null,
      version: '0',
    };
    store.dictTypes = [created, ...store.dictTypes];
    return ok(created);
  }),
  http.put('/admin/v1/system/dict-types/:typeId', async ({ request, params }) => {
    const body = await bodyOf(request);
    const id = String(params.typeId);
    const target = store.dictTypes.find((t) => t.id === id);
    if (!target) {
      return fail('DICT_TYPE_NOT_FOUND', '字典类型不存在或已被删除', 404);
    }
    const updated = {
      ...target,
      dictName: typeof body.dictName === 'string' ? body.dictName : target.dictName,
      remark: (body.remark as string | null) ?? target.remark,
      version: String(Number(target.version) + 1),
    };
    store.dictTypes = store.dictTypes.map((t) => (t.id === id ? updated : t));
    return ok(updated);
  }),
  http.delete('/admin/v1/system/dict-types/:typeId', ({ params }) => {
    const id = String(params.typeId);
    if (store.dictItems.some((item) => item.dictTypeId === id)) {
      return fail('DICT_TYPE_HAS_ITEMS', '字典类型下存在条目，无法删除', 400);
    }
    store.dictTypes = store.dictTypes.filter((t) => t.id !== id);
    return empty();
  }),
  http.get('/admin/v1/system/dict-types/:typeId/items', ({ request, params }) => {
    const typeId = String(params.typeId);
    const qs = urlOf(request).searchParams;
    // 兼容两种消费口径：useDict 大页取全量返回分页信封 { records, total }（响应兼容数组形状）；
    // 既有管理端缺省不带查询参数 → 条目数组直出
    const rows = store.dictItems.filter((item) => item.dictTypeId === typeId);
    if (qs.get('page') !== null || qs.get('size') !== null) {
      return page(rows, rows.length);
    }
    return ok(rows);
  }),
  http.post('/admin/v1/system/dict-types/:typeId/items', async ({ request, params }) => {
    const body = await bodyOf(request);
    const created = {
      id: genId(),
      dictTypeId: String(params.typeId),
      itemLabel: String(body.itemLabel ?? ''),
      itemValue: String(body.itemValue ?? ''),
      sortOrder: Number(body.sortOrder ?? 0),
      status: 'ENABLED' as const,
      version: '0',
    };
    store.dictItems = [...store.dictItems, created];
    return ok(created);
  }),
  http.put('/admin/v1/system/dict-items/:itemId', async ({ request, params }) => {
    const body = await bodyOf(request);
    const id = String(params.itemId);
    const target = store.dictItems.find((item) => item.id === id);
    if (!target) {
      return fail('DICT_ITEM_NOT_FOUND', '字典条目不存在或已被删除', 404);
    }
    const updated = {
      ...target,
      itemLabel: typeof body.itemLabel === 'string' ? body.itemLabel : target.itemLabel,
      itemValue: typeof body.itemValue === 'string' ? body.itemValue : target.itemValue,
      sortOrder: Number(body.sortOrder ?? target.sortOrder),
      version: String(Number(target.version) + 1),
    };
    store.dictItems = store.dictItems.map((item) => (item.id === id ? updated : item));
    return ok(updated);
  }),
  http.delete('/admin/v1/system/dict-items/:itemId', ({ params }) => {
    const id = String(params.itemId);
    store.dictItems = store.dictItems.filter((item) => item.id !== id);
    return empty();
  }),
];
