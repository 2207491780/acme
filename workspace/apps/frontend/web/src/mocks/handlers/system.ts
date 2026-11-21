import { http } from 'msw';
import {
  findUnknownWatermarkPlaceholders,
  isAccentKey,
  isContentWidth,
  isDensityValue,
  isHexColor,
  isRadiusScale,
  isSemanticColorOverrides,
  isThemeModeValue,
} from '@acme/core';
import type { AdminCapabilityView, AdminDeptView, AdminDictTypeView, AdminMenuView, AdminPermissionView, AdminPositionView, AdminRoleView, AdminSystemConfigView, AdminTenantPackageView, AdminTenantView, AdminUserView, RoleDataScope } from '@acme/core';
import * as db from '../data';
import { bodyOf, empty, fail, ok, page, pagingOf, slicePage, urlOf } from '../utils';

/** 系统域 handler（platform-system）：me / 租户 / 套餐 / 用户 / 角色 / 部门 / 岗位 / 权限点 / 菜单 / 字典。
 *  resolver 内联（msw 2.x Params 泛型按路径逐 handler 推断，不抽公共别名）。
 *  REQ-20261114-120104 扩展：部门树 CRUD / 岗位 CRUD / 角色数据范围（PUT data-scope）/
 *  用户部门筛选（deptId 子树递归）与用户部门/岗位字段。
 *  REQ-20261121-022910 扩展：套餐五端点 CRUD / 租户 packageId 扩展（POST/PUT，向后兼容）/
 *  me 菜单树按套餐过滤（确认②：∩ 套餐菜单集 + 祖先链自动补全 + BUTTON 级联）。
 *  REQ-20261121-054613 扩展：GET /capabilities 能力目录（仅启用、id asc）/ 套餐 create/update/detail
 *  capabilities 全量替换校验（未知码 → TENANT_CAPABILITY_INVALID）/ me.capabilities 生效集装配
 *  （绑定套餐=套餐能力集、未绑定=全部启用码；internal 面不属 admin mock 范围，不设 handler）。 */

/** 通用 keyword 过滤（命中任一字段子串，大小写不敏感）。 */
function matchKeyword<T>(rows: T[], keyword: string | null, fields: (row: T) => (string | null | undefined)[]): T[] {
  if (!keyword) {
    return rows;
  }
  const needle = keyword.toLowerCase();
  return rows.filter((row) => fields(row).some((value) => value?.toLowerCase().includes(needle)));
}

const keywordOf = (request: Request): string | null => urlOf(request).searchParams.get('keyword');

// ————— 菜单树筛选（REQ-20261120-213603） —————

/** 菜单类型/启用状态合法枚举（对齐后端 MenuType/EnableStatus；非法值→BAD_REQUEST）。 */
const MENU_TYPE_ENUM = ['DIRECTORY', 'MENU', 'BUTTON'];
const MENU_STATUS_ENUM = ['ENABLED', 'DISABLED'];

/** 菜单树筛选：返回「命中节点∪其全部祖先」构成的树（保持父子层级；命中节点的子孙不自动带出）。 */
function filterMenuTree(nodes: AdminMenuView[], predicate: (node: AdminMenuView) => boolean): AdminMenuView[] {
  const selected = new Set<number>();
  const collect = (list: AdminMenuView[], ancestorIds: number[]) => {
    for (const node of list) {
      if (predicate(node)) {
        selected.add(node.id);
        for (const ancestorId of ancestorIds) selected.add(ancestorId);
      }
      collect(node.children, [...ancestorIds, node.id]);
    }
  };
  collect(nodes, []);
  // 重建树：未选中节点的子树必无命中（祖先链已全部入选），整枝剪除
  const rebuild = (list: AdminMenuView[]): AdminMenuView[] =>
    list
      .filter((node) => selected.has(node.id))
      .map((node) => ({ ...node, children: rebuild(node.children) }));
  return rebuild(nodes);
}

// ————— 部门树内存工具（REQ-20261114-120104） —————

/** 深拷贝种子树（嵌套 children 与 db 常量隔离，避免 dev 内存态写入种子）。 */
function cloneDeptTree(nodes: AdminDeptView[]): AdminDeptView[] {
  return nodes.map((node) => ({ ...node, children: cloneDeptTree(node.children) }));
}

function findDept(nodes: AdminDeptView[], id: string): AdminDeptView | null {
  for (const node of nodes) {
    if (node.id === id) {
      return node;
    }
    const hit = findDept(node.children, id);
    if (hit) {
      return hit;
    }
  }
  return null;
}

function patchDeptTree(nodes: AdminDeptView[], id: string, patch: (node: AdminDeptView) => AdminDeptView): AdminDeptView[] {
  return nodes.map((node) => (node.id === id
    ? patch(node)
    : { ...node, children: patchDeptTree(node.children, id, patch) }));
}

function removeFromDeptTree(nodes: AdminDeptView[], id: string): AdminDeptView[] {
  return nodes
    .filter((node) => node.id !== id)
    .map((node) => ({ ...node, children: removeFromDeptTree(node.children, id) }));
}

/** candidate 是否位于 ancestor 子树内（防环：上级部门不能是自身或其子部门）。 */
function isDeptDescendant(nodes: AdminDeptView[], ancestorId: string, candidateId: string): boolean {
  const ancestor = findDept(nodes, ancestorId);
  return !!ancestor && !!findDept(ancestor.children, candidateId);
}

/** 部门子树 id 全集（用户列表 deptId 过滤的含子部门递归口径）。 */
function collectDeptIds(root: AdminDeptView, into: Set<string>): void {
  into.add(root.id);
  for (const child of root.children) {
    collectDeptIds(child, into);
  }
}

const DATA_SCOPE_ENUM: RoleDataScope[] = ['ALL', 'DEPT', 'DEPT_AND_CHILD', 'SELF', 'CUSTOM'];

/** 全菜单树节点 id 全集（REQ-20261121-022910：套餐 menuIds 合法性校验 TENANT_PACKAGE_MENU_INVALID 口径）。 */
function collectAllMenuIds(nodes: AdminMenuView[]): Set<number> {
  const ids = new Set<number>();
  const walk = (list: AdminMenuView[]): void => {
    for (const node of list) {
      ids.add(node.id);
      walk(node.children);
    }
  };
  walk(nodes);
  return ids;
}

/**
 * 菜单树套餐过滤（REQ-20261121-022910 确认②）：下发集 = 套餐菜单集 ∪ 命中节点 BUTTON 级联，
 * 再自动补全祖先链（叶在包内而目录不在时补祖先，保证树完整渲染）；读时实时过滤（无物化拷贝）。
 */
function filterMenusByPackage(nodes: AdminMenuView[], packageMenuIds: Set<number>): AdminMenuView[] {
  const selected = new Set<number>();
  // 第一遍：命中套餐集的节点入选，其直接子节点（BUTTON）级联入选
  const walk = (list: AdminMenuView[]): void => {
    for (const node of list) {
      if (packageMenuIds.has(node.id)) {
        selected.add(node.id);
        for (const child of node.children) {
          selected.add(child.id);
        }
      }
      walk(node.children);
    }
  };
  walk(nodes);
  // 第二遍：祖先链自动补全（选中节点的全部祖先入选）
  const collectAncestors = (list: AdminMenuView[], ancestorIds: number[]): void => {
    for (const node of list) {
      if (selected.has(node.id)) {
        for (const ancestorId of ancestorIds) selected.add(ancestorId);
      }
      collectAncestors(node.children, [...ancestorIds, node.id]);
    }
  };
  collectAncestors(nodes, []);
  // 重建树：未入选节点整枝剪除（其子树必无选中节点）
  const rebuild = (list: AdminMenuView[]): AdminMenuView[] =>
    list
      .filter((node) => selected.has(node.id))
      .map((node) => ({ ...node, children: rebuild(node.children) }));
  return rebuild(nodes);
}

/** 审计可写集合：dev mock 的内存态允许增删改（仅内存，不落盘）。 */
const store = {
  tenants: [...db.tenants],
  /** 租户套餐主行（REQ-20261121-022910）+ 套餐-菜单绑定（套餐 id → 菜单 id 全集）。
   *  REQ-20261121-054613 补：能力目录（平台级只读）+ 套餐-能力绑定（套餐 id → 能力码全集）。 */
  tenantPackages: [...db.tenantPackages] as AdminTenantPackageView[],
  packageMenuIds: { ...db.tenantPackageMenus } as Record<string, number[]>,
  capabilities: [...db.capabilities] as AdminCapabilityView[],
  packageCapabilities: { ...db.tenantPackageCapabilities } as Record<string, string[]>,
  users: [...db.users],
  /** 本人画像（REQ-20261119-082531）：PUT /me 与头像上传改写此行，GET /me 回显。 */
  meUser: { ...db.me.user } as AdminUserView,
  /** 本人偏好（REQ-20261121-200415 契约②④两域；sys_user_preference dev 演示行）：
   *  messagePush 默认 false；themePreference=null=未设行（GET 默认值口径）。 */
  preferences: { messagePush: false, themePreference: null } as {
    messagePush: boolean;
    themePreference: Record<string, unknown> | null;
  },
  /** 本人推送令牌（REQ-20261119-082531 契约⑤；token upsert/删除演示态）。 */
  pushTokens: [] as { token: string; platform: string }[],
  roles: [...db.roles],
  depts: cloneDeptTree(db.depts),
  positions: [...db.positions],
  permissions: [...db.permissions],
  menus: [...db.menus],
  dictTypes: [...db.dictTypes],
  dictItems: [...db.dictItems],
  /** 角色数据范围=CUSTOM 时的部门集（sys_role_dept 演示；非 CUSTOM 清空）。 */
  roleDeptIds: {} as Record<string, string[]>,
  /** 租户系统配置（REQ-20261116-230354；每租户一行的 dev 演示态）。 */
  systemConfig: { ...db.systemConfig } as AdminSystemConfigView,
};

let nextId = 10000;
const genId = (): string => String(nextId++);

const nowIso = (): string => new Date().toISOString();

// ————— 租户套餐装配（REQ-20261121-022910） —————

/** 套餐视图实时装配：menuCount=绑定表行数、tenantCount=绑定租户数（套餐/绑定变更即时反映）。 */
function packageViewOf(pkg: AdminTenantPackageView): AdminTenantPackageView {
  return {
    ...pkg,
    menuCount: (store.packageMenuIds[pkg.id] ?? []).length,
    tenantCount: store.tenants.filter((tenant) => tenant.packageId === pkg.id).length,
  };
}

/** 租户视图装配：packageName 依当前套餐主行实时冗余（套餐改名即时反映；未绑定=null）。 */
function tenantViewOf(tenant: AdminTenantView): AdminTenantView {
  const packageName = tenant.packageId
    ? store.tenantPackages.find((pkg) => pkg.id === tenant.packageId)?.packageName ?? null
    : null;
  return { ...tenant, packageName };
}

/** 套餐 body 载荷归一：packageName 必填 ≤128（trim）、remark ≤255（空串归一 null）、
 *  menuIds[] 合法性校验、capabilities[] 合法性校验（REQ-20261121-054613：null/缺省=空集全量替换，
 *  未知/未启用能力码 → TENANT_CAPABILITY_INVALID 14-0006，与后端错误码同语义）。
 *  失败返回 fail 响应；成功返回归一后的载荷。 */
function parsePackageBody(
  body: Record<string, unknown>,
): { ok: true; packageName: string; remark: string | null; menuIds: number[]; capabilities: string[] } | { ok: false; response: ReturnType<typeof fail> } {
  const packageName = typeof body.packageName === 'string' ? body.packageName.trim() : '';
  if (!packageName || packageName.length > 128) {
    return { ok: false, response: fail('PARAM_INVALID', '套餐名称必填且不超过 128 字符', 400) };
  }
  if (body.remark !== undefined && body.remark !== null && typeof body.remark !== 'string') {
    return { ok: false, response: fail('PARAM_INVALID', '备注类型非法', 400) };
  }
  const remark = typeof body.remark === 'string' && body.remark.trim() ? body.remark.trim() : null;
  if (remark && remark.length > 255) {
    return { ok: false, response: fail('PARAM_INVALID', '备注长度不超过 255 字符', 400) };
  }
  const menuIds = Array.isArray(body.menuIds) ? (body.menuIds as unknown[]).map(Number) : [];
  const knownMenus = collectAllMenuIds(store.menus);
  if (menuIds.some((menuId) => !Number.isFinite(menuId) || !knownMenus.has(menuId))) {
    return { ok: false, response: fail('TENANT_PACKAGE_MENU_INVALID', 'menuIds 含不存在或已删除的菜单', 400) };
  }
  // 能力码（REQ-20261121-054613）：可分配集合=目录中 status=ENABLED 的能力码（目录端点同口径）；
  // 码集为字符串原样提交（不做归一），缺省/null=空集（全量替换语义同 menuIds）
  const capabilities = Array.isArray(body.capabilities) ? (body.capabilities as unknown[]).map(String) : [];
  const assignableCodes = new Set(
    store.capabilities.filter((item) => item.status === 'ENABLED').map((item) => item.capabilityCode),
  );
  if (capabilities.some((code) => !assignableCodes.has(code))) {
    return { ok: false, response: fail('TENANT_CAPABILITY_INVALID', 'capabilities 含未知或未启用的能力码', 400) };
  }
  return { ok: true, packageName, remark, menuIds, capabilities };
}

/** 租户 body.packageId 归一（REQ-20261121-022910：undefined=不改动既有绑定；null=解除绑定；
 *  非法（不存在）→ TENANT_PACKAGE_NOT_FOUND；数字/字符串 wire 双口径兼容）。 */
function parseTenantPackageId(body: Record<string, unknown>, fallback: string | null):
  { ok: true; packageId: string | null } | { ok: false; response: ReturnType<typeof fail> } {
  if (body.packageId === undefined) {
    return { ok: true, packageId: fallback };
  }
  if (body.packageId === null || body.packageId === '') {
    return { ok: true, packageId: null };
  }
  const packageId = String(body.packageId);
  if (!store.tenantPackages.some((pkg) => pkg.id === packageId)) {
    return { ok: false, response: fail('TENANT_PACKAGE_NOT_FOUND', '套餐不存在或已被删除', 404) };
  }
  return { ok: true, packageId };
}

/** 本人菜单树（确认② mock 过滤接线）：本人租户绑定套餐 → 实时过滤（∩ 套餐菜单集+祖先补全+BUTTON 级联）；
 *  未绑定（packageId null）= 全量下发（回归基线，行为与改造前一致）。 */
function meMenus(): AdminMenuView[] {
  const tenant = store.tenants.find((row) => row.id === db.ME_TENANT_ID);
  const packageId = tenant?.packageId ?? null;
  if (!packageId) {
    return store.menus;
  }
  return filterMenusByPackage(store.menus, new Set(store.packageMenuIds[packageId] ?? []));
}

/** 本人租户生效能力码集（REQ-20261121-054613 确认⑤）：绑定套餐 → 套餐能力码数组（读绑定表实时装配，
 *  套餐增删能力即时生效）；未绑定（packageId null）= 全部启用能力码（与菜单「不受限」语义同口径）。 */
function meCapabilities(): string[] {
  const tenant = store.tenants.find((row) => row.id === db.ME_TENANT_ID);
  const packageId = tenant?.packageId ?? null;
  if (!packageId) {
    return store.capabilities
      .filter((item) => item.status === 'ENABLED')
      .map((item) => item.capabilityCode);
  }
  return store.packageCapabilities[packageId] ?? [];
}

// ————— themePreference 校验拒绝矩阵（REQ-20261121-200415 契约③⑫；与后端结构校验器同口径）—————

const PREFERENCE_BLOB_TOP_KEYS = ['schemaVersion', 'preferences', 'extensions'];
const PREFERENCE_KEYS = [
  'mode', 'accent', 'customAccent', 'density', 'motionEnabled', 'radius', 'grayMode',
  'weakMode', 'contentWidth', 'semanticColors', 'extensions',
];
const EXT_KEY_LIMIT_BYTES = 16 * 1024;
const EXT_TOTAL_LIMIT_BYTES = 64 * 1024;

function isRecordValue(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** extensions 结构校验（契约③：仅整体结构，内容透传）：object、单键 ≤16KB、总量 ≤64KB。 */
function validatePreferenceExtensions(value: unknown): string | null {
  if (!isRecordValue(value)) {
    return 'extensions 必须为对象';
  }
  let total = 0;
  for (const [key, item] of Object.entries(value)) {
    const size = (JSON.stringify(item ?? null) ?? '').length;
    if (size > EXT_KEY_LIMIT_BYTES) {
      return 'extensions 单键序列化超过 16KB';
    }
    total += key.length + size;
    if (total > EXT_TOTAL_LIMIT_BYTES) {
      return 'extensions 总量超过 64KB';
    }
  }
  return null;
}

/** themePreference blob fail-closed 校验：合法返回 null；非法返回拒绝原因（原数据不动）。 */
function validateThemePreferenceBlob(blob: unknown): string | null {
  if (!isRecordValue(blob)) {
    return 'themePreference 必须为对象';
  }
  if (Object.keys(blob).some((key) => !PREFERENCE_BLOB_TOP_KEYS.includes(key))) {
    return 'themePreference 含未知顶层键';
  }
  if (typeof blob.schemaVersion !== 'number' || !Number.isInteger(blob.schemaVersion)) {
    return 'schemaVersion 必须为整数';
  }
  if (!isRecordValue(blob.preferences)) {
    return 'preferences 必须为对象';
  }
  if (blob.extensions !== undefined && validatePreferenceExtensions(blob.extensions)) {
    return validatePreferenceExtensions(blob.extensions);
  }
  const p = blob.preferences;
  if (Object.keys(p).some((key) => !PREFERENCE_KEYS.includes(key))) {
    return 'preferences 含未知键';
  }
  if (p.extensions !== undefined && validatePreferenceExtensions(p.extensions)) {
    return validatePreferenceExtensions(p.extensions);
  }
  if ('mode' in p && !isThemeModeValue(p.mode)) return 'mode 枚举非法';
  if ('accent' in p && !isAccentKey(p.accent)) return 'accent 枚举非法';
  if ('customAccent' in p && p.customAccent !== null && !isHexColor(p.customAccent)) {
    return 'customAccent 必须为 null 或合法 hex';
  }
  if ('density' in p && !isDensityValue(p.density)) return 'density 枚举非法';
  if ('motionEnabled' in p && typeof p.motionEnabled !== 'boolean') return 'motionEnabled 必须为布尔';
  if ('radius' in p && !isRadiusScale(p.radius)) return 'radius 枚举非法';
  if ('grayMode' in p && typeof p.grayMode !== 'boolean') return 'grayMode 必须为布尔';
  if ('weakMode' in p && typeof p.weakMode !== 'boolean') return 'weakMode 必须为布尔';
  if ('contentWidth' in p && !isContentWidth(p.contentWidth)) return 'contentWidth 枚举非法';
  if ('semanticColors' in p && !isSemanticColorOverrides(p.semanticColors)) {
    return 'semanticColors 必须为 null/合法 hex 三键对象';
  }
  return null;
}

export const systemHandlers = [
  // ---- me ----
  // REQ-20261121-054613：capabilities=当前租户生效能力码集（绑定套餐=套餐能力集/未绑定=全部启用码）
  http.get('/admin/v1/system/me', () => ok({ ...db.me, user: store.meUser, menus: meMenus(), capabilities: meCapabilities() })),

  // ---- 个人中心：本人资料/头像/偏好/推送令牌（REQ-20261119-082531） ----
  // PUT /me（契约①）：nickname 必填 ≤32；email 可空 email 格式；phone 可空 1\d{10}
  //（镜像后端 UserController 校验口径）；avatarUrl 可空（null=不改动头像语义由后端裁量为整体覆盖）
  http.put('/admin/v1/system/me', async ({ request }) => {
    const body = await bodyOf(request);
    const nickname = typeof body.nickname === 'string' ? body.nickname.trim() : '';
    if (!nickname || nickname.length > 32) {
      return fail('PROFILE_INVALID', '昵称必填且不超过 32 字符', 200);
    }
    const email = typeof body.email === 'string' && body.email.trim() ? body.email.trim() : null;
    if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      return fail('PROFILE_INVALID', '邮箱格式不正确', 200);
    }
    const phone = typeof body.phone === 'string' && body.phone.trim() ? body.phone.trim() : null;
    if (phone && !/^1\d{10}$/.test(phone)) {
      return fail('PROFILE_INVALID', '手机号格式不正确（1 开头 11 位）', 200);
    }
    const avatarUrl = typeof body.avatarUrl === 'string' && body.avatarUrl.trim() ? body.avatarUrl.trim() : null;
    store.meUser = {
      ...store.meUser,
      nickname,
      email,
      phone,
      avatarUrl,
      updatedAt: nowIso(),
      version: String(Number(store.meUser.version) + 1),
    };
    return ok({ ...db.me, user: store.meUser, menus: meMenus(), capabilities: meCapabilities() });
  }),
  // POST /admin/v1/infra/me/avatar（TL 裁决：头像上传落 infra 认证面，免 infra:file:upload；
  // multipart file → FileView；非图片/超大拒绝镜像 FileUseCase 图片校验口径）
  http.post('/admin/v1/infra/me/avatar', async ({ request }) => {
    const form = await request.formData().catch(() => null);
    const file = form?.get('file');
    if (!(file instanceof File)) {
      return fail('FILE_TYPE_REJECTED', '缺少上传文件', 400);
    }
    if (!file.type.startsWith('image/')) {
      return fail('FILE_TYPE_REJECTED', '头像仅支持图片文件', 200);
    }
    if (file.size > 5 * 1024 * 1024) {
      return fail('FILE_SIZE_EXCEEDED', '文件大小超出限制', 200);
    }
    const id = genId();
    return ok({
      id,
      originalName: file.name,
      size: file.size,
      contentType: file.type,
      url: `/files/avatar-${id}${file.name.includes('.') ? file.name.slice(file.name.lastIndexOf('.')) : '.png'}`,
      bizType: 'avatar',
      bizId: store.meUser.id,
      createdAt: nowIso(),
    });
  }),
  // preferences（REQ-20261121-200415 契约②③④⑫两域）：GET 未设行 messagePush=false+
  // themePreference=null；PUT 按域 upsert（null/缺省=不动该域，至少一域非 null 否则 400；
  // 向后兼容旧 {messagePush} 单域 PUT）；themePreference 校验拒绝矩阵（fail-closed，
  // 原数据不动）→ 400 SYS-01-0001（TL 裁决与后端 wire 原文同形）。校验口径与后端契约③
  // 一致、逐键校验器取自 core。
  http.get('/admin/v1/system/me/preferences', () =>
    ok({ messagePush: store.preferences.messagePush, themePreference: store.preferences.themePreference })),
  http.put('/admin/v1/system/me/preferences', async ({ request }) => {
    const body = await bodyOf(request);
    // 域判定：null/undefined=不动该域（契约②）；两域均非有效值 → 400
    // 错误码口径（TL 裁决 REQ-20261121-200415 + 后端 wire 原文定稿）：HTTP 400 + code
    // 'SYS-01-0001'（VALIDATION_FAILED 枚举的 wire 码，枚举名不上 wire）、data=null、
    // message=「主题偏好非法: <原因>」（两域全空对齐后端原文）——复用既有校验段，AC-12 同形。
    const hasPush = body.messagePush !== undefined && body.messagePush !== null;
    const hasTheme = body.themePreference !== undefined && body.themePreference !== null;
    if (!hasPush && !hasTheme) {
      return fail('SYS-01-0001', 'messagePush 与 themePreference 至少一项非空', 400);
    }
    if (hasPush && typeof body.messagePush !== 'boolean') {
      return fail('SYS-01-0001', '主题偏好非法: messagePush 必须为布尔', 400);
    }
    if (hasTheme) {
      const error = validateThemePreferenceBlob(body.themePreference);
      if (error) {
        return fail('SYS-01-0001', `主题偏好非法: ${error}`, 400);
      }
    }
    // 按域 upsert：仅写非 null 域，另一域原样保留（AC-1/AC-2）
    if (hasPush) store.preferences.messagePush = body.messagePush as boolean;
    if (hasTheme) store.preferences.themePreference = body.themePreference as Record<string, unknown>;
    return ok({ messagePush: store.preferences.messagePush, themePreference: store.preferences.themePreference });
  }),
  // push-tokens（契约⑤）：POST upsert（同 token 幂等）/ DELETE ?token= 删本人行（幂等成功）
  http.post('/admin/v1/system/me/push-tokens', async ({ request }) => {
    const body = await bodyOf(request);
    const token = typeof body.token === 'string' ? body.token.trim() : '';
    const platform = typeof body.platform === 'string' ? body.platform : 'web';
    if (!token) {
      return fail('PREFERENCE_INVALID', '推送 token 缺失', 200);
    }
    store.pushTokens = [
      ...store.pushTokens.filter((row) => row.token !== token),
      { token, platform },
    ];
    return empty();
  }),
  http.delete('/admin/v1/system/me/push-tokens', ({ request }) => {
    const token = urlOf(request).searchParams.get('token') ?? '';
    store.pushTokens = store.pushTokens.filter((row) => row.token !== token);
    return empty();
  }),

  // ---- 租户系统配置（REQ-20261116-230354；REQ-20261117-011514 增 captchaEnabled；REQ-20261117-025200 增页脚版权；REQ-20261117-180000 增水印模板） ----
  http.get('/admin/v1/system/config', () => ok(store.systemConfig)),
  http.put('/admin/v1/system/config', async ({ request }) => {
    const body = await bodyOf(request);
    const idle = body.lockIdleTimeoutMs;
    if (typeof body.watermarkEnabled !== 'boolean' || typeof body.captchaEnabled !== 'boolean'
      || typeof idle !== 'number' || ![0, 60_000, 300_000, 900_000].includes(idle)) {
      return fail('PARAM_INVALID', '配置参数非法：闲置时长仅支持 0/1/5/15 分钟', 200);
    }
    // 水印模板（REQ-20261117-180000）：可空（空串归一 null），≤255；未知完整占位符拒绝（镜像后端口径）
    const template = typeof body.watermarkTemplate === 'string' && body.watermarkTemplate.length > 0
      && body.watermarkTemplate.length <= 255 ? body.watermarkTemplate : null;
    if (template && findUnknownWatermarkPlaceholders(template).length > 0) {
      return fail('WATERMARK_TEMPLATE_INVALID', '水印模板包含不支持的占位符', 200);
    }
    if (body.version !== store.systemConfig.version) {
      return fail('VERSION_CONFLICT', '配置已被其他用户修改，请刷新后重试', 200);
    }
    store.systemConfig = {
      watermarkEnabled: body.watermarkEnabled,
      captchaEnabled: body.captchaEnabled,
      lockIdleTimeoutMs: idle,
      // 页脚版权（REQ-20261117-025200）：文案可空（空串归一 null），≤255；显隐布尔
      footerCopyright: typeof body.footerCopyright === 'string' && body.footerCopyright.length > 0 && body.footerCopyright.length <= 255
        ? body.footerCopyright
        : null,
      footerVisible: body.footerVisible === true,
      watermarkTemplate: template,
      version: String(Number(store.systemConfig.version) + 1),
    };
    return ok(store.systemConfig);
  }),

  // ---- 租户 ----
  // REQ-20261121-022910：行视图经 tenantViewOf 装配（packageName 实时冗余；packageId 存行）
  http.get('/admin/v1/system/tenants', ({ request }) => {
    const rows = matchKeyword(store.tenants, keywordOf(request), (tenant: AdminTenantView) => [tenant.tenantCode, tenant.tenantName])
      .map(tenantViewOf);
    return page(slicePage(rows, pagingOf(request)), rows.length);
  }),
  http.post('/admin/v1/system/tenants', async ({ request }) => {
    const body = await bodyOf(request);
    if (typeof body.tenantCode !== 'string' || typeof body.tenantName !== 'string') {
      return fail('TENANT_CODE_INVALID', '租户代码/名称缺失', 400);
    }
    // REQ-20261121-022910：可选 packageId（可空=不受限；非法 → TENANT_PACKAGE_NOT_FOUND）
    const packaged = parseTenantPackageId(body, null);
    if (!packaged.ok) {
      return packaged.response;
    }
    const created = {
      id: genId(), tenantCode: body.tenantCode, tenantName: body.tenantName,
      status: 'ENABLED' as const, remark: (body.remark as string | undefined) ?? null,
      // 租户档案三新字段（REQ-20261117-025200）：可空；expiresAt ISO-8601，null=长期
      contactName: (body.contactName as string | null | undefined) ?? null,
      contactPhone: (body.contactPhone as string | null | undefined) ?? null,
      expiresAt: (body.expiresAt as string | null | undefined) ?? null,
      packageId: packaged.packageId,
      createdAt: nowIso(), version: '0',
    };
    store.tenants = [created, ...store.tenants];
    return ok(tenantViewOf(created));
  }),
  http.put('/admin/v1/system/tenants/:tenantId', async ({ request, params }) => {
    const body = await bodyOf(request);
    const id = String(params.tenantId);
    const target = store.tenants.find((tenant) => tenant.id === id);
    if (!target) {
      return fail('TENANT_NOT_FOUND', '租户不存在或已被删除', 404);
    }
    // REQ-20261121-022910：可选 packageId（undefined=不改动既有绑定向后兼容；null=解除绑定；
    // 非法 → TENANT_PACKAGE_NOT_FOUND）
    const packaged = parseTenantPackageId(body, target.packageId ?? null);
    if (!packaged.ok) {
      return packaged.response;
    }
    const updated = {
      ...target,
      tenantName: typeof body.tenantName === 'string' ? body.tenantName : target.tenantName,
      remark: (body.remark as string | null) ?? target.remark,
      // 租户档案三新字段（REQ-20261117-025200）：随体透传（null=清空/长期）
      contactName: (body.contactName as string | null | undefined) ?? null,
      contactPhone: (body.contactPhone as string | null | undefined) ?? null,
      expiresAt: (body.expiresAt as string | null | undefined) ?? null,
      packageId: packaged.packageId,
      version: String(Number(target.version) + 1),
    };
    store.tenants = store.tenants.map((tenant) => (tenant.id === id ? updated : tenant));
    return ok(tenantViewOf(updated));
  }),
  http.put('/admin/v1/system/tenants/:tenantId/status', async ({ request, params }) => {
    const body = await bodyOf(request);
    const id = String(params.tenantId);
    store.tenants = store.tenants.map((tenant) => (tenant.id === id ? { ...tenant, status: body.status === 'DISABLED' ? 'DISABLED' : 'ENABLED' } : tenant));
    return empty();
  }),

  // ---- 服务能力目录（REQ-20261121-054613；读权限挂 system:tenant-package:list，mock 不校验权限头） ----
  // 全量启用列表（仅 status=ENABLED），排序 id asc——套餐表单「能力」checkbox 组数据源；
  // 平台级只读目录，无 CRUD 面（种子/迁移维护）
  http.get('/admin/v1/system/capabilities', () =>
    ok(store.capabilities
      .filter((item) => item.status === 'ENABLED')
      .slice()
      .sort((a, b) => Number(a.id) - Number(b.id)))),

  // ---- 租户套餐（REQ-20261121-022910 五端点；权限点 system:tenant-package:*，mock 不校验权限头） ----
  // GET 分页：keyword 对 packageName 模糊；排序 id asc；menuCount/tenantCount 实时装配
  http.get('/admin/v1/system/tenant-packages', ({ request }) => {
    const rows = matchKeyword(store.tenantPackages, keywordOf(request), (pkg: AdminTenantPackageView) => [pkg.packageName])
      .slice()
      .sort((a, b) => Number(a.id) - Number(b.id))
      .map(packageViewOf);
    return page(slicePage(rows, pagingOf(request)), rows.length);
  }),
  // POST 创建：重名 → TENANT_PACKAGE_DUPLICATE；menuIds 含不存在菜单 → TENANT_PACKAGE_MENU_INVALID；
  // capabilities 含未知/未启用能力码 → TENANT_CAPABILITY_INVALID（REQ-20261121-054613，缺省=空集）；
  // menuIds 可空=空套餐（绑定表零行）
  http.post('/admin/v1/system/tenant-packages', async ({ request }) => {
    const body = await bodyOf(request);
    const parsed = parsePackageBody(body);
    if (!parsed.ok) {
      return parsed.response;
    }
    if (store.tenantPackages.some((pkg) => pkg.packageName === parsed.packageName)) {
      return fail('TENANT_PACKAGE_DUPLICATE', '套餐名称已存在', 400);
    }
    const created: AdminTenantPackageView = {
      id: genId(),
      packageName: parsed.packageName,
      remark: parsed.remark,
      menuCount: parsed.menuIds.length,
      tenantCount: 0,
      version: '0',
      createdAt: nowIso(),
      updatedAt: nowIso(),
    };
    store.tenantPackages = [...store.tenantPackages, created];
    store.packageMenuIds[created.id] = parsed.menuIds;
    store.packageCapabilities[created.id] = parsed.capabilities;
    return ok(packageViewOf(created));
  }),
  // GET 详情：视图 + menuIds 全集 + capabilities 全集（REQ-20261121-054613）；不存在 → TENANT_PACKAGE_NOT_FOUND
  http.get('/admin/v1/system/tenant-packages/:packageId', ({ params }) => {
    const id = String(params.packageId);
    const target = store.tenantPackages.find((pkg) => pkg.id === id);
    if (!target) {
      return fail('TENANT_PACKAGE_NOT_FOUND', '套餐不存在或已被删除', 404);
    }
    return ok({ ...packageViewOf(target), menuIds: store.packageMenuIds[id] ?? [], capabilities: store.packageCapabilities[id] ?? [] });
  }),
  // PUT 更新（REQ-20261121-022910 round 2 追认后端 AdminTenantPackageUpdate @NotNull version，
  // 框架乐观锁惯例）：version 门禁先行（绑定阶段语义——缺失/非法→PARAM_INVALID 400 信封；
  // 陈旧版本→VERSION_CONFLICT）；校验同创建（重名排除自身）；menuIds/capabilities 全量替换
  // （差异同步绑定表；REQ-20261121-054613 capabilities 未知/未启用码→TENANT_CAPABILITY_INVALID）；
  // 成功后 version 递增。round 2 补：FR-024 LongToStringModule 全局——version wire 字符串口径，
  // 反序列化双收字符串/数值（比较/自增用 Number() 归一，出参 String() 化）
  http.put('/admin/v1/system/tenant-packages/:packageId', async ({ request, params }) => {
    const id = String(params.packageId);
    const target = store.tenantPackages.find((pkg) => pkg.id === id);
    if (!target) {
      return fail('TENANT_PACKAGE_NOT_FOUND', '套餐不存在或已被删除', 404);
    }
    const body = await bodyOf(request);
    const hasVersion = (typeof body.version === 'string' && body.version.trim() !== '')
      || (typeof body.version === 'number' && Number.isFinite(body.version));
    if (!hasVersion) {
      return fail('PARAM_INVALID', 'version 乐观锁版本缺失', 400);
    }
    if (Number(body.version) !== Number(target.version)) {
      return fail('VERSION_CONFLICT', '套餐已被其他用户修改，请刷新后重试', 200);
    }
    const parsed = parsePackageBody(body);
    if (!parsed.ok) {
      return parsed.response;
    }
    if (store.tenantPackages.some((pkg) => pkg.id !== id && pkg.packageName === parsed.packageName)) {
      return fail('TENANT_PACKAGE_DUPLICATE', '套餐名称已存在', 400);
    }
    const updated: AdminTenantPackageView = {
      ...target,
      packageName: parsed.packageName,
      remark: parsed.remark,
      menuCount: parsed.menuIds.length,
      version: String(Number(target.version) + 1),
      updatedAt: nowIso(),
    };
    store.tenantPackages = store.tenantPackages.map((pkg) => (pkg.id === id ? updated : pkg));
    store.packageMenuIds[id] = parsed.menuIds;
    store.packageCapabilities[id] = parsed.capabilities;
    return ok(packageViewOf(updated));
  }),
  // DELETE 删除：被任一租户绑定 → TENANT_PACKAGE_IN_USE（不删除）；否则删主行+绑定行（菜单/能力）
  http.delete('/admin/v1/system/tenant-packages/:packageId', ({ params }) => {
    const id = String(params.packageId);
    const target = store.tenantPackages.find((pkg) => pkg.id === id);
    if (!target) {
      return fail('TENANT_PACKAGE_NOT_FOUND', '套餐不存在或已被删除', 404);
    }
    if (store.tenants.some((tenant) => tenant.packageId === id)) {
      return fail('TENANT_PACKAGE_IN_USE', '套餐已被租户绑定，无法删除', 400);
    }
    store.tenantPackages = store.tenantPackages.filter((pkg) => pkg.id !== id);
    delete store.packageMenuIds[id];
    delete store.packageCapabilities[id];
    return empty();
  }),

  // ---- 用户 ----
  http.get('/admin/v1/system/users', ({ request }) => {
    const params = urlOf(request).searchParams;
    const status = params.get('status');
    let rows = matchKeyword(store.users, params.get('username'), (u: AdminUserView) => [u.username])
      .filter((u) => !status || u.status === status);
    // deptId 过滤（REQ-20261114-120104 契约「含子部门递归」）：收集部门子树 id 集后过滤
    const deptId = params.get('deptId');
    if (deptId) {
      const root = findDept(store.depts, deptId);
      if (root) {
        const ids = new Set<string>();
        collectDeptIds(root, ids);
        rows = rows.filter((u) => u.deptId != null && ids.has(u.deptId));
      } else {
        rows = [];
      }
    }
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
      deptId: body.deptId ? String(body.deptId) : null,
      positionId: body.positionId ? String(body.positionId) : null,
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
      deptId: body.deptId !== undefined ? (body.deptId ? String(body.deptId) : null) : (target.deptId ?? null),
      positionId: body.positionId !== undefined ? (body.positionId ? String(body.positionId) : null) : (target.positionId ?? null),
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

  // ---- 部门（REQ-20261114-120104） ----
  http.get('/admin/v1/system/depts', () => ok(store.depts)),
  http.post('/admin/v1/system/depts', async ({ request }) => {
    const body = await bodyOf(request);
    if (typeof body.deptName !== 'string' || !body.deptName.trim()) {
      return fail('DEPT_NAME_INVALID', '部门名称缺失', 400);
    }
    const parentId = body.parentId ? String(body.parentId) : '0';
    if (parentId !== '0' && !findDept(store.depts, parentId)) {
      return fail('DEPT_NOT_FOUND', '上级部门不存在或已被删除', 404);
    }
    const created: AdminDeptView = {
      id: genId(),
      parentId,
      deptName: body.deptName,
      sortOrder: Number(body.sortOrder ?? 0),
      status: body.status === 'DISABLED' ? 'DISABLED' : 'ENABLED',
      children: [],
      version: '0',
    };
    if (parentId === '0') {
      store.depts = [...store.depts, created];
    } else {
      store.depts = patchDeptTree(store.depts, parentId, (node) => ({ ...node, children: [...node.children, created] }));
    }
    return ok(created);
  }),
  http.put('/admin/v1/system/depts/:deptId', async ({ request, params }) => {
    const body = await bodyOf(request);
    const id = String(params.deptId);
    const target = findDept(store.depts, id);
    if (!target) {
      return fail('DEPT_NOT_FOUND', '部门不存在或已被删除', 404);
    }
    const parentId = body.parentId !== undefined ? (body.parentId ? String(body.parentId) : '0') : target.parentId;
    if (parentId === id || isDeptDescendant(store.depts, id, parentId)) {
      return fail('DEPT_PARENT_INVALID', '上级部门不能是自身或其子部门', 400);
    }
    const updated: AdminDeptView = {
      ...target,
      parentId,
      deptName: typeof body.deptName === 'string' ? body.deptName : target.deptName,
      sortOrder: body.sortOrder !== undefined ? Number(body.sortOrder) : target.sortOrder,
      status: body.status === 'DISABLED' ? 'DISABLED' : body.status === 'ENABLED' ? 'ENABLED' : target.status,
      version: String(Number(target.version) + 1),
    };
    if (parentId === target.parentId) {
      store.depts = patchDeptTree(store.depts, id, () => updated);
      return ok(updated);
    }
    // 换父：校验新父存在（防误删）→ 先摘除再挂载（根=顶层追加）
    if (parentId !== '0' && !findDept(store.depts, parentId)) {
      return fail('DEPT_NOT_FOUND', '上级部门不存在或已被删除', 404);
    }
    store.depts = removeFromDeptTree(store.depts, id);
    if (parentId === '0') {
      store.depts = [...store.depts, updated];
    } else {
      store.depts = patchDeptTree(store.depts, parentId, (node) => ({ ...node, children: [...node.children, updated] }));
    }
    return ok(updated);
  }),
  http.delete('/admin/v1/system/depts/:deptId', ({ params }) => {
    const id = String(params.deptId);
    const target = findDept(store.depts, id);
    if (!target) {
      return fail('DEPT_NOT_FOUND', '部门不存在或已被删除', 404);
    }
    // 删除守卫（契约确认记录③）：有子部门/有用户拒绝
    if (target.children.length > 0) {
      return fail('DEPT_HAS_CHILDREN', '存在子部门，无法删除', 400);
    }
    if (store.users.some((u) => u.deptId === id)) {
      return fail('DEPT_HAS_USERS', '部门下存在用户，无法删除', 400);
    }
    store.depts = removeFromDeptTree(store.depts, id);
    return empty();
  }),

  // ---- 岗位（REQ-20261114-120104） ----
  http.get('/admin/v1/system/positions', ({ request }) => {
    const rows = matchKeyword(store.positions, keywordOf(request), (p: AdminPositionView) => [p.positionCode, p.positionName]);
    return page(slicePage(rows, pagingOf(request)), rows.length);
  }),
  http.post('/admin/v1/system/positions', async ({ request }) => {
    const body = await bodyOf(request);
    const created: AdminPositionView = {
      id: genId(),
      positionCode: String(body.positionCode ?? ''),
      positionName: String(body.positionName ?? ''),
      status: body.status === 'DISABLED' ? 'DISABLED' : 'ENABLED',
      createdAt: nowIso(),
      version: '0',
    };
    store.positions = [...store.positions, created];
    return ok(created);
  }),
  http.put('/admin/v1/system/positions/:positionId', async ({ request, params }) => {
    const body = await bodyOf(request);
    const id = String(params.positionId);
    const target = store.positions.find((p) => p.id === id);
    if (!target) {
      return fail('POSITION_NOT_FOUND', '岗位不存在或已被删除', 404);
    }
    const updated: AdminPositionView = {
      ...target,
      positionName: typeof body.positionName === 'string' ? body.positionName : target.positionName,
      positionCode: typeof body.positionCode === 'string' ? body.positionCode : target.positionCode,
      status: body.status === 'DISABLED' ? 'DISABLED' : body.status === 'ENABLED' ? 'ENABLED' : target.status,
      version: String(Number(target.version) + 1),
    };
    store.positions = store.positions.map((p) => (p.id === id ? updated : p));
    return ok(updated);
  }),
  http.delete('/admin/v1/system/positions/:positionId', ({ params }) => {
    const id = String(params.positionId);
    const target = store.positions.find((p) => p.id === id);
    if (!target) {
      return fail('POSITION_NOT_FOUND', '岗位不存在或已被删除', 404);
    }
    // 被用户引用的岗位删除拒绝（契约 §API 错误码 POSITION_IN_USE）
    if (store.users.some((u) => u.positionId === id)) {
      return fail('POSITION_IN_USE', '岗位已被用户引用，无法删除', 400);
    }
    store.positions = store.positions.filter((p) => p.id !== id);
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
      dataScope: 'ALL' as const,
      version: '0',
    };
    store.roles = [created, ...store.roles];
    return ok(created);
  }),
  // 数据范围子资源（契约 §API：PUT /roles/{id}/data-scope）先于通配 PUT 识别
  http.put('/admin/v1/system/roles/:roleId/data-scope', async ({ request, params }) => {
    const body = await bodyOf(request);
    const id = String(params.roleId);
    const target = store.roles.find((role) => role.id === id);
    if (!target) {
      return fail('ROLE_NOT_FOUND', '角色不存在或已被删除', 404);
    }
    const dataScope = String(body.dataScope ?? '');
    if (!DATA_SCOPE_ENUM.includes(dataScope as RoleDataScope)) {
      return fail('DATA_SCOPE_INVALID', '数据范围枚举无效', 400);
    }
    const deptIds = Array.isArray(body.deptIds) ? (body.deptIds as unknown[]).map(String) : [];
    if (dataScope === 'CUSTOM') {
      // AC-3 镜像：CUSTOM 未传/传空 deptIds → 校验拒绝；传非法部门 → 拒绝
      if (deptIds.length === 0) {
        return fail('DATA_SCOPE_INVALID', '自定义数据范围必须携带部门集', 400);
      }
      if (deptIds.some((deptId) => !findDept(store.depts, deptId))) {
        return fail('DATA_SCOPE_INVALID', '自定义部门不存在', 400);
      }
      store.roleDeptIds[id] = deptIds;
    } else {
      delete store.roleDeptIds[id];
    }
    const updated: AdminRoleView = {
      ...target,
      dataScope: dataScope as RoleDataScope,
      version: String(Number(target.version) + 1),
    };
    store.roles = store.roles.map((role) => (role.id === id ? updated : role));
    return ok(updated);
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
  // REQ-20261120-214653：keyword（permissionCode/permissionName 模糊，与 users/roles handler 同口径）
  // + status/httpMethod 精确匹配，多条件 AND；httpMethod 为 null 的记录不命中任何动词筛选；
  // 不携带参数=现状全量分页（total=过滤后命中总数）。
  http.get('/admin/v1/system/permissions', ({ request }) => {
    const params = urlOf(request).searchParams;
    const status = params.get('status');
    const httpMethod = params.get('httpMethod');
    const rows = matchKeyword(store.permissions, keywordOf(request), (p: AdminPermissionView) => [p.permissionCode, p.permissionName])
      .filter((p) => (status ? p.status === status : true))
      .filter((p) => (httpMethod ? p.httpMethod === httpMethod : true));
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
  // GET /menus（REQ-20261120-213603）：keyword/menuType/status/visible 可选筛选（多条件 AND，
  // keyword 对 menuName/path/permissionCode 不区分大小写包含、任一命中即算）；命中=命中节点∪其全部祖先
  // （保持树形层级，命中节点子孙不自动带出）；无筛选参数=全量树（现状不变）。
  http.get('/admin/v1/system/menus', ({ request }) => {
    const params = urlOf(request).searchParams;
    const keyword = params.get('keyword');
    const menuType = params.get('menuType');
    const status = params.get('status');
    const visibleParam = params.get('visible');
    if (keyword === null && menuType === null && status === null && visibleParam === null) {
      return ok(store.menus);
    }
    // 非法枚举对齐后端口径：HTTP 400＋Result 信封 BAD_REQUEST（框架 GlobalExceptionTranslator 既有翻译）
    if (
      (menuType !== null && !MENU_TYPE_ENUM.includes(menuType)) ||
      (status !== null && !MENU_STATUS_ENUM.includes(status)) ||
      (visibleParam !== null && visibleParam !== 'true' && visibleParam !== 'false')
    ) {
      return fail('BAD_REQUEST', '非法筛选参数', 400);
    }
    const tree = filterMenuTree(store.menus, (node) => {
      if (keyword) {
        const needle = keyword.toLowerCase();
        const hit = [node.menuName, node.path, node.permissionCode]
          .some((value) => value?.toLowerCase().includes(needle));
        if (!hit) {
          return false;
        }
      }
      if (menuType !== null && node.menuType !== menuType) {
        return false;
      }
      if (status !== null && node.status !== status) {
        return false;
      }
      if (visibleParam !== null && node.visible !== (visibleParam === 'true')) {
        return false;
      }
      return true;
    });
    return ok(tree);
  }),
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
