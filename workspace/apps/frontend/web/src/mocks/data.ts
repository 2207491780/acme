import type {
  AdminAuditEventView,
  AdminCapabilityView,
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
  AdminTenantInitTemplateView,
  AdminTenantPackageView,
  AdminTenantView,
  AdminUserView,
  ExecutionView,
  ExecutorView,
  FileView,
  JobAdminView,
  LeaveRequestView,
  ProcessDefinitionView,
  ProcessInstanceView,
  TaskView,
  HistoryActivityView,
} from '@acme/core';

/**
 * MSW 内存种子数据（REQ-20261114-120103，AC-7）：形状与 @acme/core 视图类型逐字段对齐，
 * 供 dev mock 与测试 handler 共用；可变集合以最小 CRUD 支持 dev 浏览演示。
 */

/** 1x1 透明 PNG（captcha 图形占位，data-URI 直渲染）。 */
export const CAPTCHA_IMAGE_DATA = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==';

/** mock 本人所属租户 id（REQ-20261121-022910 菜单下发过滤判定用：acme=租户 1，未绑套餐=不受限）。
 *  演示租户 beta（租户 2）绑定「基础版」套餐——本人租户不绑套餐即保持既有全量下发（回归基线不受扰）。 */
export const ME_TENANT_ID = '1';

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
    // REQ-20261121-022910（契约确认⑥）：套餐权限点 69~72（V24 随 role_menu 46~49 下发超管）
    'system:tenant-package:list', 'system:tenant-package:create', 'system:tenant-package:update', 'system:tenant-package:delete',
    // REQ-20261122-233426（TENANT-5 确认⑤）：V32 种子权限点 5 个挂靠租户管理既有菜单（超管绑定随种子幂等写入）
    'system:tenant-template:list', 'system:tenant-template:create', 'system:tenant-template:update', 'system:tenant-template:delete',
    'system:tenant:usage-overview',
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
    // REQ-20261122-023213（JOB-3）：调度中心权限点 73~77（V26 种子；73 列表/历史/执行器，
    // 75 启停/触发/新建/编辑（契约⑤），76 删除；me.menus 动态形态由菜单种子 V28 下发）
    'scheduler:job:list', 'scheduler:job:create', 'scheduler:job:update', 'scheduler:job:delete',
    'auth:loginlog:list', 'auth:online:list', 'auth:online:revoke',
    // REQ-20261122-023441（V27 随 role_menu 60~64 下发超管）：workflow 五码——
    // definition:list(78)+definition:deploy(79) 为 BPM-2 本页所需；instance:manage/task:list/task:operate 为 BPM-3/4 预留
    'workflow:definition:list', 'workflow:definition:deploy',
    'workflow:instance:manage', 'workflow:task:list', 'workflow:task:operate',
  ],
  menus: [] as AdminMenuView[],
  // REQ-20261117-180000：本人部门名（deptId 4001=总部；水印 {deptName} 供给）
  deptName: '总部',
  // REQ-20261121-054613：本人租户生效能力码集（acme 未绑套餐=全量启用码种子缺省；
  // handler 按 meCapabilities() 依绑定态实时装配覆盖）
  capabilities: ['platform:workflow', 'platform:scheduler'],
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
    // REQ-20261121-022910：未绑定套餐（packageId null=不受限，行为与现状一致）
    packageId: null,
    // REQ-20261122-102954：顶层租户（parentId null=顶层，level 1）
    parentId: null, level: 1,
    // REQ-20261122-150802：无有效期=派生启用中；无覆盖=继承平台缺省
    graceUntil: null,
    createdAt: '2026-01-01T00:00:00Z', version: '0',
  },
  {
    id: '2', tenantCode: 'beta', tenantName: 'Beta 试点', status: 'ENABLED', remark: null,
    contactName: null, contactPhone: null, expiresAt: null,
    // REQ-20261121-022910：绑定「基础版」套餐（演示租户套餐过滤样例；packageName 由 handler 实时装配）
    packageId: '1',
    parentId: null, level: 1,
    graceUntil: null,
    createdAt: '2026-01-02T00:00:00Z', version: '0',
  },
  {
    // REQ-20261122-102954 两层层级样例：beta 的子租户（parentId 引用数字 id wire 字符串口径，level=父+1）
    id: '3', tenantCode: 'beta-south', tenantName: 'Beta 南区分部', status: 'ENABLED', remark: null,
    contactName: null, contactPhone: null, expiresAt: null,
    packageId: null,
    parentId: '2', level: 2,
    graceUntil: null,
    createdAt: '2026-01-03T00:00:00Z', version: '0',
  },
  {
    // REQ-20261122-150802 四态样例①宽限期：expires 已过且 grace_until 未到（仍可登录，派生宽限期）
    id: '4', tenantCode: 'gamma', tenantName: 'Gamma 宽限样例', status: 'ENABLED', remark: null,
    contactName: null, contactPhone: null,
    expiresAt: '2026-06-01T00:00:00Z', graceUntil: '2026-12-31T23:59:59Z',
    packageId: null,
    parentId: null, level: 1,
    createdAt: '2026-01-04T00:00:00Z', version: '0',
  },
  {
    // REQ-20261122-150802 四态样例②已过期：expires 与 grace 均已过（派生过期，登录被拒）
    id: '5', tenantCode: 'delta', tenantName: 'Delta 过期样例', status: 'ENABLED', remark: null,
    contactName: null, contactPhone: null,
    expiresAt: '2026-03-01T00:00:00Z', graceUntil: '2026-05-01T00:00:00Z',
    packageId: null,
    parentId: null, level: 1,
    createdAt: '2026-01-05T00:00:00Z', version: '0',
  },
  {
    // REQ-20261122-150802 四态样例③已停用：status=DISABLED（正交优先于派生态）
    id: '6', tenantCode: 'epsilon', tenantName: 'Epsilon 停用样例', status: 'DISABLED', remark: null,
    contactName: null, contactPhone: null, expiresAt: null, graceUntil: null,
    packageId: null,
    parentId: null, level: 1,
    createdAt: '2026-01-06T00:00:00Z', version: '0',
  },
];

/** 租户用量种子（REQ-20261122-150802 契约④：GET /tenants/{id}/usage mock 聚合演示；
 *  storageBytes=sys_file 未删 size 总和、userCount=sys_user 归属计数——handler 以种子为基线，
 *  userCount 叠加 store.users 中 tenantCode 命中的动态行（init 开通租户管理员即时反映）。 */
export const tenantUsageSeeds: Record<string, { storageBytes: number; userCount: number }> = {
  '1': { storageBytes: 268_435_456, userCount: 12 },
  '2': { storageBytes: 96_468_992, userCount: 5 },
  '3': { storageBytes: 15_728_640, userCount: 2 },
  '4': { storageBytes: 1_073_741_824, userCount: 3 },
  '5': { storageBytes: 4_194_304, userCount: 1 },
  '6': { storageBytes: 0, userCount: 0 },
};

/** 租户覆盖配额（REQ-20261122-150802 确认③解析链最高级：sys_tenant_quota mock 演示态；
 *  tenantId → quotaStorageBytes 字节口径（0=不限）；缺行=无覆盖（回落套餐默认/平台缺省）。 */
export const tenantQuotaOverrides: Record<string, number> = {
  // beta-south：3 GiB 覆盖样例（用量 Modal 来源层级=租户覆盖演示）
  '3': 3_221_225_472,
};

/** 租户开通模板种子（REQ-20261122-233426 确认②④：V32 sys_tenant_init_template 样例 2 条——
 *  content jsonb 形状={adminUsernamePrefix?, adminNickname?, rolePresets[], configEntries[]}；
 *  permissionCodes 仅非保留前缀（保留域=system:tenant:/infra:audit:/system:tenant-package:）；
 *  defaultPackageId 引用 tenantPackages 行（wire 字符串口径）。 */
export const tenantInitTemplates: AdminTenantInitTemplateView[] = [
  {
    id: '801', code: 'startup-basic', name: '标准起步模板', remark: '预置基础协作角色与通知配置，绑定基础版套餐',
    content: {
      adminUsernamePrefix: 'startup-',
      adminNickname: '管理员',
      rolePresets: [
        { roleKey: 'ops', roleName: '运维员', permissionCodes: ['system:user:list', 'system:user:create', 'system:user:update', 'infra:file:list', 'infra:file:upload'] },
        { roleKey: 'viewer', roleName: '只读观察', permissionCodes: ['system:user:list', 'infra:audit:list'] },
      ],
      configEntries: [
        { configKey: 'notify.mail.enabled', value: 'true' },
        { configKey: 'workspace.defaultPageSize', value: '20' },
      ],
    },
    defaultPackageId: '1',
    createdAt: '2026-02-01T00:00:00Z', updatedAt: '2026-02-01T00:00:00Z',
  },
  {
    id: '802', code: 'minimal', name: '极简模板', remark: null,
    content: {
      rolePresets: [
        { roleKey: 'member', roleName: '普通成员', permissionCodes: ['system:user:list'] },
      ],
      configEntries: [],
    },
    defaultPackageId: null,
    createdAt: '2026-02-02T00:00:00Z', updatedAt: '2026-02-03T00:00:00Z',
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
 *  httpMethod 覆盖 GET/POST/DELETE 三动词。
 *  REQ-20261125-230807（V36）：service=所属服务（path 第 4 段回填口径，三行均 system）。 */
export const permissions: AdminPermissionView[] = [
  { id: '1', permissionCode: 'system:user:list', permissionName: '用户查询', permissionType: 'API', httpMethod: 'GET', httpPath: '/admin/v1/system/users', service: 'system', status: 'ENABLED', version: '0' },
  { id: '2', permissionCode: 'system:user:create', permissionName: '用户新建', permissionType: 'API', httpMethod: 'POST', httpPath: '/admin/v1/system/users', service: 'system', status: 'ENABLED', version: '0' },
  { id: '3', permissionCode: 'system:user:delete', permissionName: '用户删除', permissionType: 'API', httpMethod: 'DELETE', httpPath: '/admin/v1/system/users/{id}', service: 'system', status: 'DISABLED', version: '0' },
];

/** 菜单树种子（REQ-20261127-234158 wire golden 对齐：id/parentId/version 均 JSON 字符串，
 *  根节点 parentId="0"——字符串零非 null；menuType 枚举名 DIRECTORY|MENU|BUTTON）。 */
export const menus: AdminMenuView[] = [
  {
    id: '1', parentId: '0', menuName: '系统管理', menuType: 'DIRECTORY', path: null, component: null, icon: 'setting',
    sortOrder: 1, visible: true, permissionCode: null, status: 'ENABLED', version: '0',
    children: [
      {
        id: '11', parentId: '1', menuName: '用户管理', menuType: 'MENU', path: '/users', component: 'users', icon: 'users',
        sortOrder: 1, visible: true, permissionCode: 'system:user:list', status: 'ENABLED', version: '0', children: [],
      },
      {
        id: '12', parentId: '1', menuName: '系统配置', menuType: 'MENU', path: '/system/config', component: 'system/config/index', icon: 'control',
        sortOrder: 2, visible: true, permissionCode: 'system:config:list', status: 'ENABLED', version: '0', children: [],
      },
      // REQ-20261120-213603：补停用/隐藏样例（菜单筛选演示命中；nav 渲染剔除 DISABLED/隐藏节点，不影响导航）
      {
        id: '13', parentId: '1', menuName: '任务调度', menuType: 'MENU', path: '/jobs', component: 'jobs/index', icon: 'clock-circle',
        sortOrder: 3, visible: true, permissionCode: 'system:job:list', status: 'DISABLED', version: '0', children: [],
      },
      {
        id: '14', parentId: '1', menuName: '文件管理', menuType: 'MENU', path: '/files', component: 'files/index', icon: 'folder',
        sortOrder: 4, visible: false, permissionCode: null, status: 'ENABLED', version: '0', children: [],
      },
      // REQ-20261121-022910（契约确认⑥）：套餐管理 MENU 叶（id 46，path /tenant-packages，
      // permission system:tenant-package:list）+ BUTTON×3（47 新增/48 编辑/49 删除，挂叶 46）
      {
        id: '46', parentId: '1', menuName: '套餐管理', menuType: 'MENU', path: '/tenant-packages', component: 'system/tenant-package/index', icon: 'gift',
        sortOrder: 5, visible: true, permissionCode: 'system:tenant-package:list', status: 'ENABLED', version: '0',
        children: [
          {
            id: '47', parentId: '46', menuName: '套餐新增', menuType: 'BUTTON', path: null, component: null, icon: null,
            sortOrder: 1, visible: true, permissionCode: 'system:tenant-package:create', status: 'ENABLED', version: '0', children: [],
          },
          {
            id: '48', parentId: '46', menuName: '套餐编辑', menuType: 'BUTTON', path: null, component: null, icon: null,
            sortOrder: 2, visible: true, permissionCode: 'system:tenant-package:update', status: 'ENABLED', version: '0', children: [],
          },
          {
            id: '49', parentId: '46', menuName: '套餐删除', menuType: 'BUTTON', path: null, component: null, icon: null,
            sortOrder: 3, visible: true, permissionCode: 'system:tenant-package:delete', status: 'ENABLED', version: '0', children: [],
          },
        ],
      },
    ],
  },
  // V29（REQ-20261122-102730 确认③）：流程中心目录（60）+ 流程定义叶（61，path /workflow）+
  // 待办中心叶（66，path /workflow/todos，permission workflow:task:list）+ BUTTON×4
  // （62 deploy/63 instance:manage/64 task:list/65 task:operate，对齐 BPM-2 fixture 先例形状；
  // 纯菜单种子无新权限点——坑 16 计数不变）
  {
    id: '60', parentId: '0', menuName: '流程中心', menuType: 'DIRECTORY', path: null, component: null, icon: 'lucide-workflow',
    sortOrder: 2, visible: true, permissionCode: null, status: 'ENABLED', version: '0',
    children: [
      {
        id: '61', parentId: '60', menuName: '流程定义', menuType: 'MENU', path: '/workflow', component: 'workflow/index', icon: 'lucide-workflow',
        sortOrder: 1, visible: true, permissionCode: 'workflow:definition:list', status: 'ENABLED', version: '0',
        children: [
          {
            id: '62', parentId: '61', menuName: '流程部署', menuType: 'BUTTON', path: null, component: null, icon: null,
            sortOrder: 1, visible: true, permissionCode: 'workflow:definition:deploy', status: 'ENABLED', version: '0', children: [],
          },
          {
            id: '63', parentId: '61', menuName: '实例管理', menuType: 'BUTTON', path: null, component: null, icon: null,
            sortOrder: 2, visible: true, permissionCode: 'workflow:instance:manage', status: 'ENABLED', version: '0', children: [],
          },
          {
            id: '64', parentId: '61', menuName: '任务查询', menuType: 'BUTTON', path: null, component: null, icon: null,
            sortOrder: 3, visible: true, permissionCode: 'workflow:task:list', status: 'ENABLED', version: '0', children: [],
          },
          {
            id: '65', parentId: '61', menuName: '任务办理', menuType: 'BUTTON', path: null, component: null, icon: null,
            sortOrder: 4, visible: true, permissionCode: 'workflow:task:operate', status: 'ENABLED', version: '0', children: [],
          },
        ],
      },
      {
        id: '66', parentId: '60', menuName: '待办中心', menuType: 'MENU', path: '/workflow/todos', component: 'workflow/todos/index', icon: 'lucide-check-square',
        sortOrder: 2, visible: true, permissionCode: 'workflow:task:list', status: 'ENABLED', version: '0', children: [],
      },
    ],
  },
];

export const dictTypes: AdminDictTypeView[] = [
  { id: '8801', dictCode: 'release_env', dictName: '发布环境', dictKind: 'FLAT', status: 'ENABLED', remark: null, version: '0' },
];

/** 租户套餐 fixtures（REQ-20261121-022910，AC-14）：≥2 条样例——「基础版」被 beta 租户绑定
 *  （删除守卫 TENANT_PACKAGE_IN_USE 演示）、「全功能版」未绑定（删除成功链路演示）；
 *  menuCount/tenantCount 为种子初值，handler 依绑定表/租户集合实时推导视图；
 *  round 2：视图带乐观锁 version——wire 字符串口径（FR-024 Long→string），成功更新后 handler 递增。 */
export const tenantPackages: AdminTenantPackageView[] = [
  {
    id: '1', packageName: '基础版', remark: '仅系统管理/用户管理基础菜单', menuCount: 2, tenantCount: 1,
    // REQ-20261122-150802：套餐默认存储配额 5 GiB（V31 quota_storage_bytes；null=继承平台缺省）
    quotaStorageBytes: 5_368_709_120,
    version: '0', createdAt: '2026-01-03T00:00:00Z', updatedAt: '2026-01-03T00:00:00Z',
  },
  {
    id: '2', packageName: '全功能版', remark: null, menuCount: 7, tenantCount: 0,
    quotaStorageBytes: null,
    version: '0', createdAt: '2026-01-04T00:00:00Z', updatedAt: '2026-01-04T00:00:00Z',
  },
];

/** 套餐-菜单绑定演示态（sys_tenant_package_menu：套餐 id → 菜单 id 全集，uk(package_id,menu_id)）；
 *  基础版绑目录+叶（1/11），全功能版含套餐管理叶及其 BUTTON（46~49，按钮随叶子级联下发）。 */
export const tenantPackageMenus: Record<string, number[]> = {
  '1': [1, 11],
  '2': [1, 11, 12, 46, 47, 48, 49],
};

/** 服务能力目录 fixtures（REQ-20261121-054613 V25 种子同款 2 行：platform:workflow 工作流 /
 *  platform:scheduler 调度中心，均 ENABLED——即 WS-BPM/WS-JOB 的租户开通位；平台级只读目录）。 */
export const capabilities: AdminCapabilityView[] = [
  { id: '1', capabilityCode: 'platform:workflow', capabilityName: '工作流', status: 'ENABLED' },
  { id: '2', capabilityCode: 'platform:scheduler', capabilityName: '调度中心', status: 'ENABLED' },
];

/** 套餐-能力绑定演示态（sys_tenant_package_capability：套餐 id → 能力码全集，uk(package_id,capability_id)；
 *  REQ-20261121-054613 全量替换语义——基础版开通工作流、全功能版全开；租户生效集=绑定套餐能力集）。 */
export const tenantPackageCapabilities: Record<string, string[]> = {
  '1': ['platform:workflow'],
  '2': ['platform:workflow', 'platform:scheduler'],
};

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

/** 请假单样例（REQ-20261124-213008 契约⑥⑦）：含 instanceId 非空/可空两形态与终态（DONE）演示行。 */
export const leaveRequests: LeaveRequestView[] = [
  {
    id: '8001', userName: 'admin', leaveType: 'ANNUAL', reason: '年假出行',
    startTime: '2026-11-30T01:00:00Z', endTime: '2026-12-02T10:00:00Z',
    status: 'APPROVING', instanceId: '9305', businessKey: 'LEAVE-seed-0001',
  },
  {
    id: '8002', userName: 'admin', leaveType: 'SICK', reason: '感冒就医',
    startTime: '2026-11-20T01:00:00Z', endTime: '2026-11-21T10:00:00Z',
    status: 'DONE', instanceId: null, businessKey: 'LEAVE-seed-0002',
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

/**
 * 调度中心任务定义 fixtures（REQ-20261122-023213 JOB-3）：形状对齐 core JobAdminView（17 字段，
 * id/version wire 字符串）。REQ-20261127-234158 wire golden 对齐：视图键长名 cronExpr/fixedRateMs，
 * fixedRateMs/timeoutMs wire 字符串（LongToStringModule），shardTotal 数值。
 * V2 种子 4 行（JOB-2 裁决）：appName=platform-infra、全部 DISABLED、
 * timeout 60s、misfire=FIRE_ONCE_NOW——cronExpr=配置默认值，与 infra 运行时 acme.*.retention-cron 可能漂移。
 */
export const schedulerJobs: JobAdminView[] = [
  {
    id: '9101', jobKey: 'file-retention-cleanup', appName: 'platform-infra',
    handler: 'fileRetentionCleanupJob', triggerType: 'CRON', cronExpr: '0 0 3 * * *',
    fixedRateMs: null, onceFireAt: null, param: null,
    retryPolicy: { maxAttempts: 3, initialIntervalMs: 5000 },
    shardTotal: 1, timeoutMs: '60000', misfirePolicy: 'FIRE_ONCE_NOW',
    status: 'DISABLED', nextFireTime: null,
    createdAt: '2026-11-22T00:00:00Z', version: '0',
  },
  {
    id: '9102', jobKey: 'login-log-retention-cleanup', appName: 'platform-infra',
    handler: 'loginLogRetentionCleanupJob', triggerType: 'CRON', cronExpr: '0 30 3 * * *',
    fixedRateMs: null, onceFireAt: null, param: null,
    retryPolicy: { maxAttempts: 3, initialIntervalMs: 5000 },
    shardTotal: 1, timeoutMs: '60000', misfirePolicy: 'FIRE_ONCE_NOW',
    status: 'DISABLED', nextFireTime: null,
    createdAt: '2026-11-22T00:00:00Z', version: '0',
  },
  {
    id: '9103', jobKey: 'exception-log-retention-cleanup', appName: 'platform-infra',
    handler: 'exceptionLogRetentionCleanupJob', triggerType: 'CRON', cronExpr: '0 40 3 * * *',
    fixedRateMs: null, onceFireAt: null, param: null,
    retryPolicy: { maxAttempts: 3, initialIntervalMs: 5000 },
    shardTotal: 1, timeoutMs: '60000', misfirePolicy: 'FIRE_ONCE_NOW',
    status: 'DISABLED', nextFireTime: null,
    createdAt: '2026-11-22T00:00:00Z', version: '0',
  },
  {
    id: '9104', jobKey: 'audit-retention-cleanup', appName: 'platform-infra',
    handler: 'auditRetentionCleanupJob', triggerType: 'CRON', cronExpr: '0 40 4 * * *',
    fixedRateMs: null, onceFireAt: null, param: null,
    retryPolicy: { maxAttempts: 3, initialIntervalMs: 5000 },
    shardTotal: 1, timeoutMs: '60000', misfirePolicy: 'FIRE_ONCE_NOW',
    status: 'DISABLED', nextFireTime: null,
    createdAt: '2026-11-22T00:00:00Z', version: '0',
  },
];

/**
 * 调度中心执行历史 fixtures（sch_job_execution 面）：状态覆盖 SUCCESS/FAILED/RUNNING/PENDING，
 * 触发方式覆盖 SCHEDULE/MANUAL；fire_time 窗口过滤与倒序由 handler 承担。
 */
export const schedulerExecutions: ExecutionView[] = [
  {
    id: '9501', definitionId: '9101', jobKey: 'file-retention-cleanup', triggerType: 'SCHEDULE',
    fireTime: '2026-11-21T03:00:00Z', state: 'SUCCESS', attempt: 0, shardIndex: null,
    result: { deleted: 12 }, error: null, costMs: 2000, traceId: 'trace-sch-1',
  },
  {
    id: '9502', definitionId: '9103', jobKey: 'exception-log-retention-cleanup', triggerType: 'SCHEDULE',
    fireTime: '2026-11-21T03:40:00Z', state: 'FAILED', attempt: 1, shardIndex: null,
    result: null, error: 'DISPATCH_TIMEOUT', costMs: 10000, traceId: 'trace-sch-2',
  },
  {
    id: '9503', definitionId: '9104', jobKey: 'audit-retention-cleanup', triggerType: 'MANUAL',
    fireTime: '2026-11-21T09:00:00Z', state: 'RUNNING', attempt: 0, shardIndex: null,
    result: null, error: null, costMs: null, traceId: 'trace-sch-3',
  },
  {
    id: '9504', definitionId: '9102', jobKey: 'login-log-retention-cleanup', triggerType: 'SCHEDULE',
    fireTime: '2026-11-21T03:30:00Z', state: 'PENDING', attempt: 0, shardIndex: null,
    result: null, error: null, costMs: null, traceId: 'trace-sch-4',
  },
  {
    id: '9505', definitionId: '9101', jobKey: 'file-retention-cleanup', triggerType: 'RETRY',
    fireTime: '2026-11-20T03:00:00Z', state: 'TIMEOUT', attempt: 2, shardIndex: 0,
    result: null, error: 'TIMEOUT', costMs: 60000, traceId: 'trace-sch-5',
  },
];

/** 调度中心执行器 fixtures（sch_executor_registry 面）：ONLINE/OFFLINE 样例（stale 30s 由后端判定）。 */
export const schedulerExecutors: ExecutorView[] = [
  {
    appName: 'platform-infra', instanceId: 'uuid-infra-0001', host: '10.0.0.11', port: 9881,
    handlers: ['file-retention-cleanup', 'login-log-retention-cleanup', 'exception-log-retention-cleanup', 'audit-retention-cleanup'],
    heartbeatAt: '2026-11-22T02:59:50Z', status: 'ONLINE',
  },
  {
    appName: 'platform-system', instanceId: 'uuid-system-0001', host: '10.0.0.12', port: 9882,
    handlers: [],
    heartbeatAt: '2026-11-22T02:40:00Z', status: 'OFFLINE',
  },
];

/** 最小 BPMN XML fixture（REQ-20261122-023441：设计器详情回显/导入演示；
 *  含 acme: 扩展属性与 XOR 条件/default，形状由 @acme/core serializeBpmnModel 保证）。 */
export const sampleLeaveBpmnXml = [
  '<?xml version="1.0" encoding="UTF-8"?>',
  '<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:acme="http://acme.dev/bpmn/ext/v1" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" id="Definitions_1" targetNamespace="http://acme.dev/bpmn">',
  '  <bpmn:process id="Process_1" isExecutable="true">',
  '    <bpmn:startEvent id="start-1" name="提交申请"/>',
  '    <bpmn:userTask id="task-1" name="主管审批" acme:assignee="${initiator}" acme:assigneeStrategy="ALL"/>',
  '    <bpmn:serviceTask id="service-1" name="落库通知" acme:serviceRef="notifyService" acme:retryPolicy="3"/>',
  '    <bpmn:endEvent id="end-1"/>',
  '    <bpmn:sequenceFlow id="flow-1" sourceRef="start-1" targetRef="task-1"/>',
  '    <bpmn:sequenceFlow id="flow-2" sourceRef="task-1" targetRef="service-1"/>',
  '    <bpmn:sequenceFlow id="flow-3" sourceRef="service-1" targetRef="end-1"/>',
  '  </bpmn:process>',
  '</bpmn:definitions>',
].join('\n');

/** 流程定义 fixtures（REQ-20261122-023441：契约对齐 ProcessDefinitionView——id/version wire 字符串、
 *  列表 bpmnXml 置空由 handler 落实；DEPLOYED/deployedAt/DISABLED 各态占位）。 */
/** BPM-3 任务 fixtures（REQ-20261122-102730 契约⑤投影：todo/done 行带 definitionKey/Name/businessKey/initiator，
 *  wire 字符串 id；覆盖 CREATED/CLAIMED/COMPLETED 全态——todo=前两条，done=已办结条）。 */
export const workflowTasks: TaskView[] = [
  {
    id: '9401', instanceId: '9301', nodeId: 'task-1', nodeName: '主管审批',
    assignee: null, status: 'CREATED', claimTime: null, completeTime: null,
    createTime: '2026-11-22T01:30:05Z',
    definitionKey: 'expense-claim', definitionName: '报销申请', businessKey: 'EXP-20261122-001', initiator: 'admin',
  },
  {
    id: '9402', instanceId: '9302', nodeId: 'task-1', nodeName: '主管审批',
    assignee: 'admin', status: 'CLAIMED', claimTime: '2026-11-22T03:10:00Z', completeTime: null,
    createTime: '2026-11-22T02:40:00Z',
    definitionKey: 'expense-claim', definitionName: '报销申请', businessKey: 'EXP-20261122-002', initiator: 'ops',
  },
  {
    id: '9403', instanceId: '9304', nodeId: 'task-1', nodeName: '主管审批',
    assignee: 'admin', status: 'COMPLETED', claimTime: '2026-11-21T10:00:00Z', completeTime: '2026-11-21T11:00:00Z',
    createTime: '2026-11-21T09:30:00Z',
    definitionKey: 'expense-claim', definitionName: '报销申请', businessKey: 'EXP-20261121-001', initiator: 'admin',
  },
  // REQ-20261122-150750（BPM-4）审批场景样例：被委托认领任务（委托链 owner→boss→admin 落 admin 办理面）
  {
    id: '9404', instanceId: '9306', nodeId: 'task-1', nodeName: '主管审批',
    assignee: 'admin', status: 'CLAIMED', claimTime: '2026-11-22T04:20:00Z', completeTime: null,
    createTime: '2026-11-22T04:10:00Z',
    definitionKey: 'expense-claim', definitionName: '报销申请', businessKey: 'EXP-20261122-004', initiator: 'ops',
  },
];

/** BPM-3 实例 fixtures（契约对齐 ProcessInstanceView：wire 字符串 id；definitionName=契约⑤投影）。
 *  覆盖 RUNNING/SUSPENDED/COMPLETED/TERMINATED 全态与 suspendReason 三因样例。 */
export const workflowInstances: ProcessInstanceView[] = [
  {
    id: '9301', definitionId: '9002', definitionKey: 'expense-claim', definitionName: '报销申请',
    businessKey: 'EXP-20261122-001', initiator: 'admin', status: 'RUNNING',
    suspendReason: null, startTime: '2026-11-22T01:30:00Z', endTime: null,
    variables: { amount: 1280, reason: '差旅报销' },
    activeNodes: [{ nodeId: 'task-1', nodeName: '主管审批', nodeType: 'UserTask', enterAt: '2026-11-22T01:30:05Z' }],
    tasks: [{ id: '9401', instanceId: '9301', nodeId: 'task-1', nodeName: '主管审批', assignee: null, status: 'CREATED', claimTime: null, completeTime: null, createTime: '2026-11-22T01:30:05Z' }],
  },
  {
    id: '9302', definitionId: '9002', definitionKey: 'expense-claim', definitionName: '报销申请',
    businessKey: 'EXP-20261122-002', initiator: 'ops', status: 'RUNNING',
    suspendReason: null, startTime: '2026-11-22T02:40:00Z', endTime: null,
    variables: { amount: 260, reason: '办公用品' },
    activeNodes: [{ nodeId: 'task-1', nodeName: '主管审批', nodeType: 'UserTask', enterAt: '2026-11-22T02:40:10Z' }],
    tasks: [{ id: '9402', instanceId: '9302', nodeId: 'task-1', nodeName: '主管审批', assignee: 'admin', status: 'CLAIMED', claimTime: '2026-11-22T03:10:00Z', completeTime: null, createTime: '2026-11-22T02:40:00Z' }],
  },
  {
    id: '9303', definitionId: '9002', definitionKey: 'expense-claim', definitionName: '报销申请',
    businessKey: 'EXP-20261122-003', initiator: 'admin', status: 'SUSPENDED',
    // 契约状态机：SUSPENDED 三因=运维挂起|SERVICE_TASK_FAILED|NO_ASSIGNEE_RESOLVED（此处服务任务失败因）
    suspendReason: 'SERVICE_TASK_FAILED', startTime: '2026-11-22T00:15:00Z', endTime: null,
    variables: { amount: 9900, reason: '设备采购' },
    activeNodes: [], tasks: [],
  },
  {
    id: '9304', definitionId: '9002', definitionKey: 'expense-claim', definitionName: '报销申请',
    businessKey: 'EXP-20261121-001', initiator: 'admin', status: 'COMPLETED',
    suspendReason: null, startTime: '2026-11-21T09:30:00Z', endTime: '2026-11-21T11:05:00Z',
    variables: { amount: 320, reason: '团队聚餐' },
    activeNodes: [], tasks: [],
  },
  {
    id: '9305', definitionId: '9002', definitionKey: 'expense-claim', definitionName: '报销申请',
    businessKey: 'EXP-20261120-009', initiator: 'ops', status: 'TERMINATED',
    suspendReason: null, startTime: '2026-11-20T14:00:00Z', endTime: '2026-11-20T16:30:00Z',
    variables: null,
    activeNodes: [], tasks: [],
  },
  {
    id: '9306', definitionId: '9002', definitionKey: 'expense-claim', definitionName: '报销申请',
    businessKey: 'EXP-20261122-004', initiator: 'ops', status: 'RUNNING',
    suspendReason: null, startTime: '2026-11-22T04:10:00Z', endTime: null,
    variables: { amount: 660, reason: '委托审批样例' },
    activeNodes: [{ nodeId: 'task-1', nodeName: '主管审批', nodeType: 'UserTask', enterAt: '2026-11-22T04:10:05Z' }],
    tasks: [{ id: '9404', instanceId: '9306', nodeId: 'task-1', nodeName: '主管审批', assignee: 'admin', status: 'CLAIMED', claimTime: '2026-11-22T04:20:00Z', completeTime: null, createTime: '2026-11-22T04:10:00Z' }],
  },
];

/** BPM-3 历史链 fixtures（实例 id → HistoryActivityView 非分页 List；ENTER/LEAVE 配对可算停留时长，
 *  REJECT_EDGE nodeId=sequenceFlow id 供只读流程图边标红，TIMER_FIRE=定时触发样例）。 */
export const workflowHistories: Record<string, HistoryActivityView[]> = {
  '9301': [
    { id: '9601', nodeId: 'start-1', nodeType: 'StartEvent', action: 'ENTER', assignee: null, startAt: '2026-11-22T01:30:00Z', endAt: null, detail: null },
    { id: '9602', nodeId: 'start-1', nodeType: 'StartEvent', action: 'LEAVE', assignee: null, startAt: '2026-11-22T01:30:02Z', endAt: '2026-11-22T01:30:04Z', detail: null },
    { id: '9603', nodeId: 'flow-2', nodeType: 'sequenceFlow', action: 'REJECT_EDGE', assignee: 'ops', startAt: '2026-11-22T01:45:00Z', endAt: null, detail: '驳回：金额超限回流' },
    { id: '9604', nodeId: 'task-1', nodeType: 'UserTask', action: 'ENTER', assignee: null, startAt: '2026-11-22T01:30:05Z', endAt: null, detail: null },
  ],
  '9302': [
    { id: '9605', nodeId: 'start-1', nodeType: 'StartEvent', action: 'ENTER', assignee: null, startAt: '2026-11-22T02:40:00Z', endAt: null, detail: null },
    { id: '9606', nodeId: 'start-1', nodeType: 'StartEvent', action: 'LEAVE', assignee: null, startAt: '2026-11-22T02:40:08Z', endAt: '2026-11-22T02:40:10Z', detail: null },
    { id: '9607', nodeId: 'task-1', nodeType: 'UserTask', action: 'ENTER', assignee: null, startAt: '2026-11-22T02:40:10Z', endAt: null, detail: null },
  ],
  // REQ-20261122-150750（BPM-4）审批场景历史：StartEvent ENTER 样例（驳回候选合法目标）
  // + delegate ENTER 污染记录（detail.delegate=委托 owner 落库形态）——驳回候选口径修正用例锚点
  '9306': [
    { id: '9620', nodeId: 'start-1', nodeType: 'StartEvent', action: 'ENTER', assignee: null, startAt: '2026-11-22T04:10:00Z', endAt: null, detail: null },
    { id: '9621', nodeId: 'start-1', nodeType: 'StartEvent', action: 'LEAVE', assignee: null, startAt: '2026-11-22T04:10:02Z', endAt: '2026-11-22T04:10:05Z', detail: null },
    { id: '9622', nodeId: 'task-1', nodeType: 'UserTask', action: 'ENTER', assignee: 'boss', startAt: '2026-11-22T04:10:05Z', endAt: '2026-11-22T04:18:00Z', detail: '{"delegate":"boss","comment":"出差期间委托 boss 代审"}' },
    { id: '9623', nodeId: 'task-1', nodeType: 'UserTask', action: 'LEAVE', assignee: 'boss', startAt: '2026-11-22T04:18:00Z', endAt: '2026-11-22T04:18:30Z', detail: null },
    { id: '9624', nodeId: 'task-1', nodeType: 'UserTask', action: 'ENTER', assignee: 'admin', startAt: '2026-11-22T04:18:30Z', endAt: null, detail: null },
  ],
  '9303': [
    { id: '9608', nodeId: 'start-1', nodeType: 'StartEvent', action: 'ENTER', assignee: null, startAt: '2026-11-22T00:15:00Z', endAt: null, detail: null },
    { id: '9609', nodeId: 'service-1', nodeType: 'ServiceTask', action: 'TIMER_FIRE', assignee: null, startAt: '2026-11-22T00:20:00Z', endAt: null, detail: '服务任务重试超限' },
  ],
  '9304': [
    { id: '9610', nodeId: 'start-1', nodeType: 'StartEvent', action: 'ENTER', assignee: null, startAt: '2026-11-21T09:30:00Z', endAt: null, detail: null },
    { id: '9611', nodeId: 'start-1', nodeType: 'StartEvent', action: 'LEAVE', assignee: null, startAt: '2026-11-21T09:30:02Z', endAt: '2026-11-21T09:30:04Z', detail: null },
    { id: '9612', nodeId: 'task-1', nodeType: 'UserTask', action: 'ENTER', assignee: 'admin', startAt: '2026-11-21T09:30:05Z', endAt: null, detail: null },
    { id: '9613', nodeId: 'task-1', nodeType: 'UserTask', action: 'LEAVE', assignee: 'admin', startAt: '2026-11-21T11:00:00Z', endAt: '2026-11-21T11:00:30Z', detail: '同意' },
    { id: '9614', nodeId: 'service-1', nodeType: 'ServiceTask', action: 'ENTER', assignee: null, startAt: '2026-11-21T11:00:30Z', endAt: null, detail: null },
    { id: '9615', nodeId: 'service-1', nodeType: 'ServiceTask', action: 'LEAVE', assignee: null, startAt: '2026-11-21T11:04:30Z', endAt: '2026-11-21T11:05:00Z', detail: null },
    { id: '9616', nodeId: 'end-1', nodeType: 'EndEvent', action: 'ENTER', assignee: null, startAt: '2026-11-21T11:05:00Z', endAt: null, detail: null },
  ],
  '9305': [
    { id: '9617', nodeId: 'start-1', nodeType: 'StartEvent', action: 'ENTER', assignee: null, startAt: '2026-11-20T14:00:00Z', endAt: null, detail: null },
    { id: '9618', nodeId: 'start-1', nodeType: 'StartEvent', action: 'LEAVE', assignee: null, startAt: '2026-11-20T14:00:05Z', endAt: '2026-11-20T14:00:08Z', detail: null },
    { id: '9619', nodeId: 'task-1', nodeType: 'UserTask', action: 'ENTER', assignee: null, startAt: '2026-11-20T14:00:08Z', endAt: null, detail: null },
  ],
};

export const workflowDefinitions: ProcessDefinitionView[] = [
  {
    id: '9001', key: 'leave-approval', name: '请假审批', version: '3', status: 'DRAFT',
    bpmnXml: null, layoutJson: null,
    createdAt: '2026-11-21T02:00:00Z', deployedAt: null,
  },
  {
    id: '9002', key: 'expense-claim', name: '报销申请', version: '1', status: 'DEPLOYED',
    // BPM-3（REQ-20261122-102730）：9002 补 running 实例链的流程图渲染源（详情回显；列表置空由 handler 落实）
    bpmnXml: sampleLeaveBpmnXml, layoutJson: null,
    createdAt: '2026-11-20T08:00:00Z', deployedAt: '2026-11-20T09:30:00Z',
  },
  {
    id: '9003', key: 'asset-retirement', name: '资产报废', version: '2', status: 'DISABLED',
    bpmnXml: null, layoutJson: null,
    createdAt: '2026-11-19T10:00:00Z', deployedAt: '2026-11-19T11:00:00Z',
  },
];
