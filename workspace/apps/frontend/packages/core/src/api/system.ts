import { apiFetch, apiJson, buildQuery } from '../http/client';
import type { ThemePreferences } from '../preferences';
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

/** 菜单树筛选参数（REQ-20261120-213603）：全部可选，提供多个时 AND 语义；空值/undefined 不进查询串。
 *  命中语义（与后端一致）：满足全部已提供条件的节点=命中节点，返回树=命中节点∪其全部祖先。 */
export interface MenuTreeFilters {
  /** 关键字：对 menuName/path/permissionCode 不区分大小写包含匹配，任一字段命中即算命中。 */
  keyword?: string;
  /** 菜单类型精确匹配（DIRECTORY|MENU|BUTTON）。 */
  menuType?: 'DIRECTORY' | 'MENU' | 'BUTTON';
  /** 启用状态精确匹配（ENABLED|DISABLED）。 */
  status?: 'ENABLED' | 'DISABLED';
  /** 可见性精确匹配（false=隐藏）。 */
  visible?: boolean;
}

/** 当前登录者视图（契约对齐 AdminMeView；FR-008 权限声明与菜单可见性依据）。
 *  REQ-20261117-180000 扩展 deptName（本人部门名，水印 {deptName} 供给；未分配/缺失=null）。
 *  REQ-20261121-054613 扩展 capabilities（当前租户生效能力码集——绑定套餐=套餐能力集、
 *  未绑定=全部启用码；web 特性开关数据源，UI 消费点=WS-BPM/WS-JOB 页面（后续轮次）；
 *  可选=旧口径 wire 兼容，meStore 整体透传不消费）。 */
export interface AdminMeView {
  user: AdminUserView;
  roles: string[];
  permissions: string[];
  menus: AdminMenuView[];
  deptName: string | null;
  /** 生效能力码集（REQ-20261121-054613；缺失=旧口径未扩展，消费方按无能力降级）。 */
  capabilities?: string[];
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

/** 主题偏好 blob（REQ-20261121-200415 THEME-3 契约②③）：schemaVersion + preferences
 *  （preferences 可含 extensions 扩展域，core preferences 注册机制口径）；后端校验与 core
 *  字段集对齐，extensions 仅整体结构校验、内容透传。 */
export interface ThemePreferenceBlob {
  schemaVersion: number;
  preferences: ThemePreferences;
  /** 顶层扩展域（契约③ blob 顶层白名单可省键；前端常态经 preferences.extensions 携带，
   *  顶层形状为后端校验器同形容忍面——结构校验、内容透传）。 */
  extensions?: Record<string, unknown>;
}

/**
 * 用户偏好视图（REQ-20261119-082531 契约⑤；REQ-20261121-200415 契约②④扩两域；
 * sys_user_preference.prefs jsonb，本人单行按域 upsert）：
 * - messagePush 维持既有默认 false；
 * - themePreference=null=未设行（后端不内置主题默认值，前端以 DEFAULT_PREFERENCES 兜底，契约④）。
 */
export interface UserPreferenceView {
  messagePush: boolean;
  themePreference: ThemePreferenceBlob | null;
}

/** 偏好更新载荷（契约②按域 upsert）：null/缺省=不动该域；至少一域非 null 否则 400；
 *  向后兼容既有 {messagePush} 单域 PUT（themePreference 不被触碰）。 */
export interface UserPreferenceUpdate {
  messagePush?: boolean | null;
  themePreference?: ThemePreferenceBlob | null;
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
 *  REQ-20261117-025200 扩展联系人/联系电话/有效期（可空；expiresAt ISO-8601 字符串，null=长期）。
 *  REQ-20261121-022910 扩展绑定套餐（可空=不受限；packageId wire 字符串口径，packageName 冗余下发便于列表直显）。
 *  REQ-20261122-102954 扩展 parentId/level（子租户层级；parentId null/缺失=顶层，level 1=顶层上限 3；
 *  两字段可选=旧口径 wire 兼容，消费方按缺省 1/顶层降级）。
 *  REQ-20261122-150802（TENANT-4）扩展 graceUntil/quotaStorageBytes：graceUntil=宽限期截止
 *  （ISO-8601，null/缺失=未启用宽限；派生态=expires 过期且 grace 未过时仍可登录）；
 *  quotaStorageBytes=解析后有效存储配额（字节；0=不限；解析链=租户覆盖→套餐默认→平台缺省）。 */
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
  /** 宽限期截止（REQ-20261122-150802；ISO-8601 字符串；null/缺失=未启用宽限；
   *  expires 过期且宽限未到=可登录（宽限期），宽限也过=派生过期）。 */
  graceUntil?: string | null;
  /** 绑定套餐 id（REQ-20261121-022910；null/缺失=不受限，菜单下发与现状一致）。 */
  packageId?: string | null;
  /** 绑定套餐名称（冗余下发；未绑定/套餐已删=null）。 */
  packageName?: string | null;
  /** 父租户 id（REQ-20261122-102954；wire 字符串口径；null/缺失=顶层租户）。 */
  parentId?: string | null;
  /** 层级（REQ-20261122-102954；1=顶层，上限 3；缺失=旧口径按 1 兜底）。 */
  level?: number;
  /** 解析后有效存储配额（REQ-20261122-150802；字节；0=不限；解析链=租户覆盖→套餐默认→平台缺省；
   *  可选=旧口径 wire 兼容，缺失按平台缺省兜底展示）。 */
  quotaStorageBytes?: number | null;
  createdAt: string;
  version: string;
}

/** 租户树节点（REQ-20261122-102954 契约⑨：GET /tenants/tree 全量树形平台侧下发——
 *  行字段同 AdminTenantView（含 status/level/套餐），children 嵌套；parent 下拉/层级展示数据源）。 */
export interface AdminTenantTreeNode extends AdminTenantView {
  children: AdminTenantTreeNode[];
}

/** 租户开通视图（REQ-20261122-102954 契约⑤⑨对齐 AdminTenantInitView）：开通租户行 + 初始
 *  管理员回显（adminUsername=归属新建租户编码的管理员登录名）。 */
export interface AdminTenantInitView extends AdminTenantView {
  adminUsername: string;
}

/** 配额来源层级（REQ-20261122-150802 确认③解析链：租户覆盖 > 套餐默认 > 平台缺省）。 */
export type TenantQuotaSource = 'TENANT_OVERRIDE' | 'PACKAGE' | 'PLATFORM_DEFAULT';

/** 租户用量视图（REQ-20261122-150802 契约⑦④：GET /tenants/{id}/usage 实时旁路聚合
 *  （@IgnoreTenant）；list 不带 usage（裁决=重），仅此端点按需拉取。
 *  quotaStorageBytes=解析后有效配额（字节；0=不限）；quotaSource=来源层级标注。 */
export interface TenantUsageView {
  /** 存储用量（字节；sys_file 未删 size 总和，实时口径）。 */
  storageBytes: number;
  /** 用户数（sys_user 归属租户计数）。 */
  userCount: number;
  /** 有效存储配额（字节；0=不限；解析链=租户覆盖→套餐默认→平台缺省）。 */
  quotaStorageBytes: number;
  /** 配额来源层级标注（用量 Modal 展示口径）。 */
  quotaSource: TenantQuotaSource;
}

/** 租户套餐视图（REQ-20261121-022910 契约对齐 AdminTenantPackageView；id wire 字符串口径；
 *  menuCount=绑定菜单行数、tenantCount=绑定租户数由后端实时推导；审计列 createdAt/updatedAt。
 *  round 2 追认：后端框架乐观锁惯例——视图带 version；round 2 补：common-serialization
 *  LongToStringModule（FR-024）全局生效——Long 序列化输出 JSON 字符串，version wire 字符串口径）。 */
export interface AdminTenantPackageView {
  id: string;
  packageName: string;
  remark: string | null;
  /** 套餐绑定菜单数（sys_tenant_package_menu 行数）。 */
  menuCount: number;
  /** 绑定该套餐的租户数。 */
  tenantCount: number;
  /** 套餐默认存储配额（REQ-20261122-150802；字节；null/缺失=继承平台缺省；
   *  低于租户覆盖优先级——解析链=租户覆盖→套餐默认→平台缺省）。 */
  quotaStorageBytes?: number | null;
  /** 乐观锁版本（wire 字符串，Long→string；后端 AdminTenantPackageUpdate @NotNull version，编辑时随体回传）。 */
  version: string;
  createdAt: string;
  updatedAt: string;
}

/** 服务能力目录视图（REQ-20261121-054613 契约对齐 AdminCapabilityView；id wire 字符串口径 FR-024）。
 *  平台级目录（无 tenant_id）、无版本列（只读参考数据，不走乐观锁编辑）；
 *  GET /capabilities 仅返回 status=ENABLED 行（后端过滤），排序 id asc。 */
export interface AdminCapabilityView {
  id: string;
  capabilityCode: string;
  capabilityName: string;
  status: 'ENABLED' | 'DISABLED';
}

/** 套餐详情（=视图 + menuIds 绑定菜单全集；REQ-20261121-022910 §套餐详情）。
 *  REQ-20261121-054613 扩展 capabilities（绑定能力码全集——编辑表单回显数据源，
 *  保存时随 capabilities 全量替换）。 */
export interface AdminTenantPackageDetail extends AdminTenantPackageView {
  menuIds: number[];
  /** 绑定能力码全集（REQ-20261121-054613；能力码字符串口径，非 id）。 */
  capabilities: string[];
}

/** 套餐创建/更新载荷（REQ-20261121-022910 §套餐创建/更新：packageName 必填 ≤128、remark ≤255、
 *  menuIds[] 可空=空套餐；更新时 menuIds 全量替换绑定表。
 *  round 2 追认：后端 AdminTenantPackageUpdate 带 @NotNull version（框架乐观锁惯例）——
 *  编辑随体提交（取 detail.version 回显值）；创建不带 version。
 *  round 2 补：LongToStringDeserializer 双收字符串/数值——页面按字符串口径提交。 */
export interface TenantPackageSavePayload {
  packageName: string;
  remark?: string | null;
  menuIds: number[];
  /** 服务能力码集（REQ-20261121-054613；全量替换口径同 menuIds——前端创建/更新均显式提交，
   *  缺省/null=空集；未知能力码 → TENANT_CAPABILITY_INVALID 14-0006）。 */
  capabilities?: string[];
  /** 乐观锁版本（仅更新携带，字符串 wire 口径；后端 @NotNull，缺失→400 参数错误）。 */
  version?: string;
}

/** 租户套餐接口域（REQ-20261121-022910 五端点；权限点 system:tenant-package:list/create/update/delete，
 *  detail 归 list 权限；业务错误码 TENANT_PACKAGE_DUPLICATE/NOT_FOUND/IN_USE/MENU_INVALID）。
 *  REQ-20261121-054613 扩展：create/update 随体 capabilities 全量替换（未知码 → TENANT_CAPABILITY_INVALID），
 *  detail 回传 capabilities 绑定全集。 */
export const tenantPackageApi = {
  /** 套餐分页（keyword 对 packageName 模糊匹配；排序 id asc；signal 为可选取消信号，透传 apiFetch）。 */
  page: (params: { page?: number; size?: number; keyword?: string } = {}, signal?: AbortSignal): Promise<PageResult<AdminTenantPackageView>> =>
    apiFetch<PageResult<AdminTenantPackageView>>(`/admin/v1/system/tenant-packages?${buildQuery(params)}`, { signal }),
  /** 新建套餐（重名 → TENANT_PACKAGE_DUPLICATE；menuIds 含不存在菜单 → TENANT_PACKAGE_MENU_INVALID）。 */
  create: (payload: TenantPackageSavePayload): Promise<AdminTenantPackageView> =>
    apiJson<AdminTenantPackageView>('/admin/v1/system/tenant-packages', 'POST', payload),
  /** 套餐详情（含 menuIds；不存在 → TENANT_PACKAGE_NOT_FOUND）。 */
  detail: (packageId: string): Promise<AdminTenantPackageDetail> =>
    apiFetch<AdminTenantPackageDetail>(`/admin/v1/system/tenant-packages/${packageId}`),
  /** 更新套餐（校验同创建；menuIds 全量替换）。 */
  update: (packageId: string, payload: TenantPackageSavePayload): Promise<AdminTenantPackageView> =>
    apiJson<AdminTenantPackageView>(`/admin/v1/system/tenant-packages/${packageId}`, 'PUT', payload),
  /** 删除套餐（被任一租户绑定 → TENANT_PACKAGE_IN_USE 拒绝；否则删主行+绑定行）。
   *  void 端点：emptyAsSuccess 兼容空体 2xx（mock empty() 惯例）与 Result 信封双口径。 */
  remove: (packageId: string): Promise<void> =>
    apiFetch<void>(`/admin/v1/system/tenant-packages/${packageId}`, { method: 'DELETE' }, { emptyAsSuccess: true }),
};

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

/** 权限点列表筛选参数（REQ-20261120-214653；全部可选，多条件 AND）。
 *  keyword 沿用后端既有 permissionCode/permissionName 模糊匹配口径（不扩展 httpPath）；
 *  status=httpMethod 均精确匹配（httpMethod 为 null 的记录不命中任何动词筛选）。 */
export interface PermissionListFilters {
  keyword?: string;
  status?: 'ENABLED' | 'DISABLED';
  httpMethod?: string;
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
    apiJson<AdminMeView>('/admin/v1/system/me', 'PUT', payload),
  /** 上传本人头像（契约②+TL 裁决两步上传第一步）：multipart POST /admin/v1/infra/me/avatar
   *  → FileView(url)；url 由调用方经 meUpdate.avatarUrl 提交后才落 sys_user.avatar_url。
   *  旧头像文件不删（后端保留期任务自然清理）。 */
  meAvatarUpload: (file: File): Promise<FileView> => {
    const form = new FormData();
    form.append('file', file);
    return apiFetch<FileView>(ME_AVATAR_ENDPOINT, { method: 'POST', body: form });
  },
  /** 读取本人偏好（REQ-20261121-200415 契约②④两域；未设行 messagePush=false+themePreference=null）。 */
  mePreferences: (): Promise<UserPreferenceView> =>
    apiFetch<UserPreferenceView>('/admin/v1/system/me/preferences'),
  /** 更新本人偏好（契约②按域 upsert）：null/缺省域不被触碰，至少一域非 null 否则 400。 */
  mePreferencesUpdate: (payload: UserPreferenceUpdate): Promise<UserPreferenceView> =>
    apiJson<UserPreferenceView>('/admin/v1/system/me/preferences', 'PUT', payload),
  /** 注册推送令牌（契约⑤；本人 token upsert——同 token 幂等；void 端点兼容空体）。 */
  pushTokenRegister: (payload: { token: string; platform: PushTokenPlatform }): Promise<void> =>
    apiJson<void>(PUSH_TOKEN_ENDPOINT, 'POST', payload, { emptyAsSuccess: true }),
  /** 移除推送令牌（契约⑤；DELETE ?token= 删本人行；幂等成功）。 */
  pushTokenRemove: (token: string): Promise<void> =>
    apiFetch<void>(`${PUSH_TOKEN_ENDPOINT}?token=${encodeURIComponent(token)}`, { method: 'DELETE' }, { emptyAsSuccess: true }),

  // ---- 租户系统配置（REQ-20261116-230354；GET 认证即可，PUT 需 system:config:update） ----
  /** 读取当前租户配置（无配置行返回缺省 true/false/0/"0"，REQ-20261117-011514 captchaEnabled=false）。 */
  configGet: (): Promise<AdminSystemConfigView> => apiFetch<AdminSystemConfigView>('/admin/v1/system/config'),
  /** 更新当前租户配置（upsert；version 乐观锁，成功 version+1）。 */
  configUpdate: (payload: AdminSystemConfigUpdate): Promise<AdminSystemConfigView> =>
    apiJson<AdminSystemConfigView>('/admin/v1/system/config', 'PUT', payload),

  // ---- 服务能力目录（REQ-20261121-054613 TENANT-2；权限挂既有 system:tenant-package:list——
  //      能力是套餐管理面的组成部分，无新权限点/菜单节点） ----
  /** 能力目录（全量启用列表：仅 status=ENABLED、排序 id asc；套餐表单「能力」checkbox 组数据源）。
   *  signal 为可选取消信号，透传 apiFetch。 */
  capabilityList: (signal?: AbortSignal): Promise<AdminCapabilityView[]> =>
    apiFetch<AdminCapabilityView[]>('/admin/v1/system/capabilities', { signal }),

  // ---- 租户管理（TENANT_* 权限码） ----
  /** 租户分页（keyword 匹配代码/名称；signal 为可选取消信号，透传 apiFetch）。 */
  tenantList: (page: number, size: number, keyword?: string, signal?: AbortSignal): Promise<PageResult<AdminTenantView>> =>
    apiFetch<PageResult<AdminTenantView>>(`/admin/v1/system/tenants?page=${page}&size=${size}${keyword ? `&keyword=${encodeURIComponent(keyword)}` : ''}`, { signal }),
  /** 新建租户（tenantCode 全局唯一且不可改；REQ-20261117-025200 增联系人/联系电话/有效期，可空=长期；
   *  REQ-20261121-022910 增可选 packageId（可空=不受限）；
   *  REQ-20261122-102954 增可选 parentId（wire 字符串口径，null/缺省=顶层；父链深度超 3 → TENANT_DEPTH_EXCEEDED）。 */
  tenantCreate: (payload: { tenantCode: string; tenantName: string; remark?: string; contactName?: string | null; contactPhone?: string | null; expiresAt?: string | null; packageId?: number | null; parentId?: string | null }): Promise<AdminTenantView> =>
    apiJson<AdminTenantView>('/admin/v1/system/tenants', 'POST', payload),
  /** 租户开通模板（REQ-20261122-102954 契约⑤⑨）：平台侧一次建租户+tenant_admin 角色+初始管理员+绑定；
   *  parentId 同 create（wire 字符串，null/缺省=顶层，父链超深 → TENANT_DEPTH_EXCEEDED）；
   *  管理员归属新建租户编码（sys_user.tenant_id=tenant_code，username 全局唯一由后端校验）。 */
  tenantInit: (payload: {
    tenantCode: string;
    tenantName: string;
    adminUsername: string;
    adminNickname?: string;
    remark?: string;
    contactName?: string | null;
    contactPhone?: string | null;
    expiresAt?: string | null;
    packageId?: number | null;
    parentId?: string | null;
  }): Promise<AdminTenantInitView> =>
    apiJson<AdminTenantInitView>('/admin/v1/system/tenants/init', 'POST', payload),
  /** 租户改名/备注/联系人/有效期（version 乐观锁）；REQ-20261121-022910 增可选 packageId
   *  （number|null，null=解除绑定；向后兼容——不传即不改动既有绑定）；
   *  REQ-20261122-102954 增可选 parentId（改父=后端重校验环+深度并重算后代 level；
   *  环 → TENANT_CYCLE、深度超限 → TENANT_DEPTH_EXCEEDED）；
   *  REQ-20261122-150802 增可选 graceUntil（ISO-8601，null=清除宽限——update 收宽限字段，确认⑥）。 */
  tenantUpdate: (tenantId: string, payload: { tenantName: string; remark?: string; contactName?: string | null; contactPhone?: string | null; expiresAt?: string | null; graceUntil?: string | null; packageId?: number | null; parentId?: string | null; version: string }): Promise<AdminTenantView> =>
    apiJson<AdminTenantView>(`/admin/v1/system/tenants/${tenantId}`, 'PUT', payload),
  /** 停用/启用租户（停用即"删除"，FR-007；REQ-20261122-102954 契约⑦：停用后端级联停用全部后代
   *  并逐租户撤会话，恢复父不自动恢复子——前端不重复提交级联）。 */
  tenantStatus: (tenantId: string, status: 'ENABLED' | 'DISABLED'): Promise<void> =>
    apiJson<void>(`/admin/v1/system/tenants/${tenantId}/status`, 'PUT', { status }),
  /** 租户用量（REQ-20261122-150802 契约⑦：GET /tenants/{id}/usage，权限 system:tenant:list；
   *  实时旁路聚合 storageBytes/userCount + 有效配额/来源层级——用量 Modal 按需拉取，list 不带 usage）；
   *  signal 为可选取消信号，透传 apiFetch。 */
  tenantUsage: (tenantId: string, signal?: AbortSignal): Promise<TenantUsageView> =>
    apiFetch<TenantUsageView>(`/admin/v1/system/tenants/${tenantId}/usage`, { signal }),
  /** 租户覆盖配额（REQ-20261122-150802 契约⑦：PUT /tenants/{id}/quota，权限 system:tenant:update；
   *  quotaStorageBytes 字节口径（0=不限），null=清除覆盖回落套餐默认/平台缺省；返回更新后租户视图）。 */
  tenantQuota: (tenantId: string, quotaStorageBytes: number | null): Promise<AdminTenantView> =>
    apiJson<AdminTenantView>(`/admin/v1/system/tenants/${tenantId}/quota`, 'PUT', { quotaStorageBytes }),
  /** 租户层级树（REQ-20261122-102954 契约⑨：GET /tenants/tree 全量树形平台侧下发，
   *  行字段同 AdminTenantView（status/level/套餐）+children 嵌套；parent 下拉与层级展示数据源。
   *  page/size 为可选分页参数：全量树不截断，仅用于统一列表请求携带分页口径（menuTree 同先例）；
   *  signal 为可选取消信号，透传 apiFetch）。 */
  tenantTree: (page?: number, size?: number, signal?: AbortSignal): Promise<AdminTenantTreeNode[]> => {
    const query = buildQuery({ page, size });
    const suffix = query ? `?${query}` : '';
    return apiFetch<AdminTenantTreeNode[]>(`/admin/v1/system/tenants/tree${suffix}`, { signal });
  },

  // ---- 用户管理（USER_* 权限码） ----
  /** 用户分页（username 匹配、status/deptId 过滤——deptId 含子部门递归为后端语义；signal 为可选取消信号，透传 apiFetch）。 */
  userList: (page: number, size: number, username?: string, status?: 'ENABLED' | 'DISABLED', deptId?: string | null, signal?: AbortSignal): Promise<PageResult<AdminUserView>> =>
    apiFetch<PageResult<AdminUserView>>(`/admin/v1/system/users?page=${page}&size=${size}${username ? `&username=${encodeURIComponent(username)}` : ''}${status ? `&status=${status}` : ''}${deptId ? `&deptId=${encodeURIComponent(deptId)}` : ''}`, { signal }),
  /** 新建用户（首密码经管理端 credentials 接口另行重置；deptId/positionId 可空=未分配）。 */
  userCreate: (payload: { username: string; nickname: string; email?: string; phone?: string; roleIds: string[]; deptId?: string | null; positionId?: string | null }): Promise<AdminUserView> =>
    apiJson<AdminUserView>('/admin/v1/system/users', 'POST', { ...payload, roleIds: payload.roleIds.map(Number), deptId: payload.deptId ? Number(payload.deptId) : null, positionId: payload.positionId ? Number(payload.positionId) : null, }),
  /** 用户改名/邮箱/手机/部门/岗位（version 乐观锁）。 */
  userUpdate: (userId: string, payload: { nickname: string; email?: string; phone?: string; deptId?: string | null; positionId?: string | null; version: string }): Promise<AdminUserView> =>
    apiJson<AdminUserView>(`/admin/v1/system/users/${userId}`, 'PUT', { ...payload, deptId: payload.deptId ? Number(payload.deptId) : null, positionId: payload.positionId ? Number(payload.positionId) : null, }),
  /** 删除用户（builtIn 内置账号由后端拒绝）。 */
  userDelete: (userId: string): Promise<void> =>
    apiFetch<void>(`/admin/v1/system/users/${userId}`, { method: 'DELETE' }),
  /** 启用/停用用户。 */
  userStatus: (userId: string, status: 'ENABLED' | 'DISABLED'): Promise<void> =>
    apiJson<void>(`/admin/v1/system/users/${userId}/status`, 'PUT', { status }),
  /** 绑定角色（全量覆盖语义）。 */
  userBindRoles: (userId: string, roleIds: string[]): Promise<void> =>
    apiJson<void>(`/admin/v1/system/users/${userId}/roles`, 'PUT', { roleIds: roleIds.map(Number) }),

  // ---- 角色管理（ROLE_* / ROLE_GRANT 权限码，P3） ----
  /** 角色分页（keyword 匹配代码/名称；signal 为可选取消信号，透传 apiFetch）。 */
  roleList: (page = 1, size = 100, keyword?: string, signal?: AbortSignal): Promise<PageResult<AdminRoleView>> =>
    apiFetch<PageResult<AdminRoleView>>(`/admin/v1/system/roles?page=${page}&size=${size}${keyword ? `&keyword=${encodeURIComponent(keyword)}` : ''}`, { signal }),
  /** 新建角色（roleCode 模式与租户代码同型；parentId 空为顶级）。 */
  roleCreate: (payload: { parentId?: number | null; roleCode: string; roleName: string; remark?: string }): Promise<AdminRoleView> =>
    apiJson<AdminRoleView>('/admin/v1/system/roles', 'POST', { ...payload, parentId: payload.parentId ?? null }),
  /** 角色改名/父级/备注（version 乐观锁）。 */
  roleUpdate: (roleId: string, payload: { roleName: string; remark?: string; parentId?: number | null; version: string }): Promise<AdminRoleView> =>
    apiJson<AdminRoleView>(`/admin/v1/system/roles/${roleId}`, 'PUT', { ...payload, parentId: payload.parentId ?? null }),
  /** 启用/停用角色。 */
  roleStatus: (roleId: string, status: 'ENABLED' | 'DISABLED'): Promise<void> =>
    apiJson<void>(`/admin/v1/system/roles/${roleId}/status`, 'PUT', { status }),
  /** 删除角色（被引用时由后端拒绝）。 */
  roleDelete: (roleId: string): Promise<void> =>
    apiFetch<void>(`/admin/v1/system/roles/${roleId}`, { method: 'DELETE' }),
  /** 角色已授权限点全量清单（授权抽屉勾选数据源）。 */
  rolePermissions: (roleId: string): Promise<AdminPermissionView[]> =>
    apiFetch<AdminPermissionView[]>(`/admin/v1/system/roles/${roleId}/permissions`),
  /** 绑定权限点（全量覆盖语义）。 */
  roleBindPermissions: (roleId: string, permissionIds: string[]): Promise<void> =>
    apiJson<void>(`/admin/v1/system/roles/${roleId}/permissions`, 'PUT', { permissionIds: permissionIds.map(Number) }),
  /** 角色已绑菜单全量清单（树形）。 */
  roleMenus: (roleId: string): Promise<AdminMenuView[]> =>
    apiFetch<AdminMenuView[]>(`/admin/v1/system/roles/${roleId}/menus`),
  /** 绑定菜单（全量覆盖语义）。 */
  roleBindMenus: (roleId: string, menuIds: number[]): Promise<void> =>
    apiJson<void>(`/admin/v1/system/roles/${roleId}/menus`, 'PUT', { menuIds }),
  /** 设置角色数据范围（REQ-20261114-120104；CUSTOM 时 deptIds 必传非空，由后端校验 DATA_SCOPE_INVALID）。 */
  roleDataScope: (roleId: string, dataScope: RoleDataScope, deptIds: string[]): Promise<AdminRoleView> =>
    apiJson<AdminRoleView>(`/admin/v1/system/roles/${roleId}/data-scope`, 'PUT', { dataScope, deptIds: deptIds.map(Number) }),

  // ---- 权限点管理（PERMISSION_* 权限码，P3） ----
  /** 权限点分页（平台级目录；signal 为可选取消信号，透传 apiFetch）。
   *  filters（REQ-20261120-214653）为可选筛选：空值不进查询串（buildQuery）；不传=现状行为
   *  （RolesPage 无参调用不变，signal 保持第 3 参位置）。 */
  permissionList: (page = 1, size = 50, signal?: AbortSignal, filters?: PermissionListFilters): Promise<PageResult<AdminPermissionView>> =>
    apiFetch<PageResult<AdminPermissionView>>(`/admin/v1/system/permissions?${buildQuery({ page, size, keyword: filters?.keyword, status: filters?.status, httpMethod: filters?.httpMethod })}`, { signal }),
  /** 新建权限点（permissionCode 模式 ^[a-z][a-z0-9:-]*$）。 */
  permissionCreate: (payload: { permissionCode: string; permissionName: string; permissionType: string; httpMethod?: string; httpPath?: string }): Promise<AdminPermissionView> =>
    apiJson<AdminPermissionView>('/admin/v1/system/permissions', 'POST', payload),
  /** 权限点改名/方法/路径（version 乐观锁；code 不可改）。 */
  permissionUpdate: (permissionId: string, payload: { permissionName: string; httpMethod?: string; httpPath?: string; version: string }): Promise<AdminPermissionView> =>
    apiJson<AdminPermissionView>(`/admin/v1/system/permissions/${permissionId}`, 'PUT', payload),
  /** 删除权限点。 */
  permissionDelete: (permissionId: string): Promise<void> =>
    apiFetch<void>(`/admin/v1/system/permissions/${permissionId}`, { method: 'DELETE' }),

  // ---- 菜单管理（MENU_* 权限码，P3） ----
  /** 菜单树（全量；前端渲染树形表格）。page/size 为可选分页参数：全量树不截断，
   *  仅用于统一列表请求携带分页口径（后端按全量返回）；signal 为可选取消信号，透传 apiFetch。
   *  REQ-20261120-213603：filters 为可选筛选（关键字＋类型/状态/可见性，多条件 AND，命中节点∪祖先链），
   *  追加为第 4 参保持既有 menuTree()/menuTree(page, size, signal) 调用兼容；空值不进查询串，不传=全量树。 */
  menuTree: (
    page?: number,
    size?: number,
    signal?: AbortSignal,
    filters?: MenuTreeFilters,
  ): Promise<AdminMenuView[]> => {
    const query = buildQuery({
      page,
      size,
      keyword: filters?.keyword,
      menuType: filters?.menuType,
      status: filters?.status,
      visible: filters?.visible,
    });
    const suffix = query ? `?${query}` : '';
    return apiFetch<AdminMenuView[]>(`/admin/v1/system/menus${suffix}`, { signal });
  },
  /** 新建菜单（parentId 空=根；menuType DIRECTORY|MENU）。 */
  menuCreate: (payload: { parentId?: number | null; menuName: string; menuType: string; path?: string; component?: string; icon?: string; sortOrder: number; visible?: boolean; permissionCode?: string }): Promise<AdminMenuView> =>
    apiJson<AdminMenuView>('/admin/v1/system/menus', 'POST', { ...payload, parentId: payload.parentId ?? null }),
  /** 菜单改名/形态/排序/可见性（version 乐观锁）。 */
  menuUpdate: (menuId: number, payload: { menuName: string; path?: string; component?: string; icon?: string; sortOrder: number; visible?: boolean; permissionCode?: string; version: string }): Promise<AdminMenuView> =>
    apiJson<AdminMenuView>(`/admin/v1/system/menus/${menuId}`, 'PUT', payload),
  /** 删除菜单。 */
  menuDelete: (menuId: number): Promise<void> =>
    apiFetch<void>(`/admin/v1/system/menus/${menuId}`, { method: 'DELETE' }),

  // ---- 部门管理（DEPT_* 权限码，REQ-20261114-120104） ----
  /** 部门树（全量、含禁用态；前端渲染树形表格/选择器）。signal 为可选取消信号，透传 apiFetch。 */
  deptTree: (signal?: AbortSignal): Promise<AdminDeptView[]> =>
    apiFetch<AdminDeptView[]>('/admin/v1/system/depts', { signal }),
  /** 新建部门（parentId 空=根部门；sortOrder/status 随体提交）。 */
  deptCreate: (payload: { parentId?: number | null; deptName: string; sortOrder: number; status?: 'ENABLED' | 'DISABLED' }): Promise<AdminDeptView> =>
    apiJson<AdminDeptView>('/admin/v1/system/depts', 'POST', { ...payload, parentId: payload.parentId ?? null }),
  /** 部门改名/父级/排序/状态（version 乐观锁）。 */
  deptUpdate: (deptId: string, payload: { parentId?: number | null; deptName: string; sortOrder: number; status?: 'ENABLED' | 'DISABLED'; version: string }): Promise<AdminDeptView> =>
    apiJson<AdminDeptView>(`/admin/v1/system/depts/${deptId}`, 'PUT', { ...payload, parentId: payload.parentId ?? null }),
  /** 删除部门（有子部门/有用户由后端拒绝：DEPT_HAS_CHILDREN/DEPT_HAS_USERS）。 */
  deptDelete: (deptId: string): Promise<void> =>
    apiFetch<void>(`/admin/v1/system/depts/${deptId}`, { method: 'DELETE' }),

  // ---- 岗位管理（POSITION_* 权限码，REQ-20261114-120104） ----
  /** 岗位分页（keyword 匹配名称/编码；signal 为可选取消信号，透传 apiFetch）。 */
  positionList: (page: number, size: number, keyword?: string, signal?: AbortSignal): Promise<PageResult<AdminPositionView>> =>
    apiFetch<PageResult<AdminPositionView>>(`/admin/v1/system/positions?page=${page}&size=${size}${keyword ? `&keyword=${encodeURIComponent(keyword)}` : ''}`, { signal }),
  /** 新建岗位（positionCode 全局唯一且不可改）。 */
  positionCreate: (payload: { positionCode: string; positionName: string; status?: 'ENABLED' | 'DISABLED' }): Promise<AdminPositionView> =>
    apiJson<AdminPositionView>('/admin/v1/system/positions', 'POST', payload),
  /** 岗位改名/编码/状态（version 乐观锁）。 */
  positionUpdate: (positionId: string, payload: { positionCode: string; positionName: string; status?: 'ENABLED' | 'DISABLED'; version: string }): Promise<AdminPositionView> =>
    apiJson<AdminPositionView>(`/admin/v1/system/positions/${positionId}`, 'PUT', payload),
  /** 删除岗位（被用户引用由后端拒绝：POSITION_IN_USE）。 */
  positionDelete: (positionId: string): Promise<void> =>
    apiFetch<void>(`/admin/v1/system/positions/${positionId}`, { method: 'DELETE' }),

  // ---- 字典管理（DICT_* 权限码，P3） ----
  /** 字典类型分页（signal 为可选取消信号，透传 apiFetch）。 */
  dictTypeList: (page = 1, size = 50, keyword?: string, signal?: AbortSignal): Promise<PageResult<AdminDictTypeView>> =>
    apiFetch<PageResult<AdminDictTypeView>>(`/admin/v1/system/dict-types?page=${page}&size=${size}${keyword ? `&keyword=${encodeURIComponent(keyword)}` : ''}`, { signal }),
  /** 新建字典类型。 */
  dictTypeCreate: (payload: { dictCode: string; dictName: string; remark?: string }): Promise<AdminDictTypeView> =>
    apiJson<AdminDictTypeView>('/admin/v1/system/dict-types', 'POST', payload),
  /** 字典类型改名/备注（version 乐观锁）。 */
  dictTypeUpdate: (typeId: string, payload: { dictName: string; remark?: string; version: string }): Promise<AdminDictTypeView> =>
    apiJson<AdminDictTypeView>(`/admin/v1/system/dict-types/${typeId}`, 'PUT', payload),
  /** 删除字典类型（含条目时由后端拒绝）。 */
  dictTypeDelete: (typeId: string): Promise<void> =>
    apiFetch<void>(`/admin/v1/system/dict-types/${typeId}`, { method: 'DELETE' }),
  /** 字典条目清单（按类型）。page/size 可选：useDict 消费时传大页取全量（确认记录⑥）；
   *  缺省不携带查询参数（既有管理端调用不变）；signal 为可选取消信号，透传 apiFetch。 */
  dictItemList: (typeId: string, page?: number, size?: number, signal?: AbortSignal): Promise<AdminDictItemView[]> => {
    const suffix = page !== undefined || size !== undefined ? `?${buildQuery({ page, size })}` : '';
    return apiFetch<AdminDictItemView[]>(`/admin/v1/system/dict-types/${typeId}/items${suffix}`, { signal });
  },
  /** 新建字典条目。 */
  dictItemCreate: (typeId: string, payload: { itemLabel: string; itemValue: string; sortOrder: number }): Promise<AdminDictItemView> =>
    apiJson<AdminDictItemView>(`/admin/v1/system/dict-types/${typeId}/items`, 'POST', payload),
  /** 字典条目改标签/值/排序（version 乐观锁）。 */
  dictItemUpdate: (itemId: string, payload: { itemLabel: string; itemValue: string; sortOrder: number; version: string }): Promise<AdminDictItemView> =>
    apiJson<AdminDictItemView>(`/admin/v1/system/dict-items/${itemId}`, 'PUT', payload),
  /** 删除字典条目。 */
  dictItemDelete: (itemId: string): Promise<void> =>
    apiFetch<void>(`/admin/v1/system/dict-items/${itemId}`, { method: 'DELETE' }),
};
