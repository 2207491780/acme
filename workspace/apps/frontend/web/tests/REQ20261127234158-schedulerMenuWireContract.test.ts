/**
 * REQ-20261127-234158（wire 契约·DTO 字段对齐 P1-10/P2-02）：scheduler 与 menus wire 对照测试。
 *
 * 【事实源=backend REQ20261127234158*WireTest.java，任一侧漂移即红】
 * 本文件为非 MSW 纯数据对照测试（禁 fetch、禁 MSW）：golden 片段与 Java 真实序列化产物
 * （JsonModules.standard() mapper writeValueAsString / readValue）字节等价，逐键断言
 * `@acme/core` 的 JobAdminView / JobSavePayload / AdminMenuView TS 声明与 wire 一致——
 * `satisfies` 编译期锁定 + 运行期键集与类型断言。
 *
 * 钉死 wire golden（后端序列化为权威，禁反向发明）：
 *   - 载荷键（JobSaveRequest wire）：jobKey/appName/handler/triggerType/cronExpr/fixedRateMs/
 *     onceFireAt/param/retryPolicy/shardTotal/shardable/timeoutMs/misfirePolicy/remark/version；
 *     短名 cron/fixedRate 不映射（cronExpr 为 null）；载荷 fixedRateMs 发 number 合法（双口径反序列化）。
 *   - 视图类型（JobView wire，LongToStringModule 全局生效）：id/version/fixedRateMs/timeoutMs 均
 *     JSON 字符串；shardTotal（Integer）为数值；cronExpr 为字符串或 null。
 *   - AdminMenuView wire：id/parentId/version 均 JSON 字符串；根节点 parentId="0"（字符串零，非 null）；
 *     menuType 枚举名 DIRECTORY|MENU|BUTTON、status 枚举名 ENABLED|DISABLED、sortOrder number、
 *     visible boolean、children 嵌套。
 *   - 已登记不改项（契约待确认项②，本批不修）：FE 视图 createdAt（后端无此字段）、shardable/remark
 *     （后端有 FE 未声明）、param/retryPolicy 的 object 声明（后端为 jsonb 文本 String）——
 *     golden 中 param/retryPolicy 按后端真实形态（字符串）呈现，其键名参与对照、类型对照显式豁免。
 */
import { describe, expect, it } from 'vitest';
import type { AdminMenuView, JobAdminView, JobSavePayload } from '@acme/core';

// ————— scheduler 视图（JobView）golden 片段 —————

/** CRON 行（fixedRateMs=null 分支）。 */
const JOB_VIEW_CRON_WIRE = [
  '{',
  '  "id": "9101",',
  '  "jobKey": "file-retention-cleanup",',
  '  "appName": "platform-infra",',
  '  "handler": "fileRetentionCleanupJob",',
  '  "triggerType": "CRON",',
  '  "cronExpr": "0 0 3 * * *",',
  '  "fixedRateMs": null,',
  '  "onceFireAt": null,',
  '  "param": "{\\"mode\\":\\"demo\\"}",',
  '  "retryPolicy": "{\\"maxAttempts\\":3,\\"initialIntervalMs\\":5000}",',
  '  "shardTotal": 1,',
  '  "timeoutMs": "60000",',
  '  "misfirePolicy": "FIRE_ONCE_NOW",',
  '  "version": "0"',
  '}',
].join('\n');

/** FIXED_RATE 行（cronExpr=null、fixedRateMs 字符串分支）。 */
const JOB_VIEW_FIXED_RATE_WIRE = [
  '{',
  '  "id": "9105",',
  '  "jobKey": "custom-report-sync",',
  '  "appName": "business-report",',
  '  "handler": null,',
  '  "triggerType": "FIXED_RATE",',
  '  "cronExpr": null,',
  '  "fixedRateMs": "300000",',
  '  "onceFireAt": null,',
  '  "param": null,',
  '  "retryPolicy": null,',
  '  "shardTotal": 2,',
  '  "timeoutMs": "30000",',
  '  "misfirePolicy": "IGNORE",',
  '  "version": "2"',
  '}',
].join('\n');

/** JobView golden 片段键集（事实源钉死的键；shardable/remark 后端有而 FE 未声明=登记不改，不入片段）。 */
const JOB_VIEW_WIRE_KEYS = [
  'id', 'jobKey', 'appName', 'handler', 'triggerType', 'cronExpr', 'fixedRateMs', 'onceFireAt',
  'param', 'retryPolicy', 'shardTotal', 'timeoutMs', 'misfirePolicy', 'version',
] as const;

/** 视图 wire 类型对照豁免键（FE object 声明 vs 后端 jsonb 文本 String——契约待确认项②登记不改）。 */
const REGISTERED_DRIFT_KEYS = ['param', 'retryPolicy'] as const;

/** FE 侧视图对照 fixture（satisfies 编译期锁定 JobAdminView 声明：Long 字段 string 化）。 */
const jobViewFixture = {
  id: '9105',
  jobKey: 'custom-report-sync',
  appName: 'business-report',
  handler: null,
  triggerType: 'FIXED_RATE',
  cronExpr: null,
  fixedRateMs: '300000',
  onceFireAt: null,
  param: null,
  retryPolicy: null,
  shardTotal: 2,
  timeoutMs: '30000',
  misfirePolicy: 'IGNORE',
  status: 'ENABLED',
  nextFireTime: null,
  createdAt: '2026-11-22T00:00:00Z',
  version: '2',
} satisfies JobAdminView;

// ————— scheduler 载荷（JobSaveRequest）对照 —————

/** FE 载荷对照 fixture（satisfies 编译期锁定 JobSavePayload 键名：长名 cronExpr/fixedRateMs，
 *  载荷 fixedRateMs 发 number——后端反序列化 number/string 双口径合法）。 */
const jobSavePayloadFixture = {
  jobKey: 'custom-report-sync',
  appName: 'business-report',
  triggerType: 'FIXED_RATE',
  cronExpr: null,
  fixedRateMs: 300000,
  onceFireAt: null,
  param: null,
  retryPolicy: null,
  shardTotal: 2,
  timeoutMs: 30000,
  misfirePolicy: 'IGNORE',
} satisfies JobSavePayload;

// ————— AdminMenuView golden 片段 —————

/** 根（DIRECTORY，parentId="0"）→ MENU 叶 → BUTTON 孙：三枚举名 + children 嵌套最小闭环。 */
const MENU_TREE_WIRE = [
  '[',
  '  {',
  '    "id": "1",',
  '    "parentId": "0",',
  '    "menuName": "系统管理",',
  '    "menuType": "DIRECTORY",',
  '    "path": null,',
  '    "component": null,',
  '    "icon": "setting",',
  '    "sortOrder": 1,',
  '    "visible": true,',
  '    "permissionCode": null,',
  '    "status": "ENABLED",',
  '    "children": [',
  '      {',
  '        "id": "46",',
  '        "parentId": "1",',
  '        "menuName": "套餐管理",',
  '        "menuType": "MENU",',
  '        "path": "/tenant-packages",',
  '        "component": "system/tenant-package/index",',
  '        "icon": "gift",',
  '        "sortOrder": 5,',
  '        "visible": true,',
  '        "permissionCode": "system:tenant-package:list",',
  '        "status": "ENABLED",',
  '        "children": [',
  '          {',
  '            "id": "47",',
  '            "parentId": "46",',
  '            "menuName": "套餐新增",',
  '            "menuType": "BUTTON",',
  '            "path": null,',
  '            "component": null,',
  '            "icon": null,',
  '            "sortOrder": 1,',
  '            "visible": true,',
  '            "permissionCode": "system:tenant-package:create",',
  '            "status": "ENABLED",',
  '            "children": [],',
  '            "version": "0"',
  '          }',
  '        ],',
  '        "version": "0"',
  '      }',
  '    ],',
  '    "version": "0"',
  '  }',
  ']',
].join('\n');

const MENU_TYPES = ['DIRECTORY', 'MENU', 'BUTTON'] as const;
const MENU_STATUSES = ['ENABLED', 'DISABLED'] as const;

/** FE 侧菜单对照 fixture（satisfies 编译期锁定 AdminMenuView 声明：id/parentId/version 字符串、根 '0'）。 */
const menuFixture = {
  id: '1',
  parentId: '0',
  menuName: '系统管理',
  menuType: 'DIRECTORY',
  path: null,
  component: null,
  icon: 'setting',
  sortOrder: 1,
  visible: true,
  permissionCode: null,
  status: 'ENABLED',
  children: [],
  version: '0',
} satisfies AdminMenuView;

/** wire 树节点（children 嵌套 + 任意键）。 */
type WireMenuNode = { children?: WireMenuNode[] } & Record<string, unknown>;

/** 递归收集树节点（扁平化，含嵌套）。 */
const flattenMenus = (nodes: WireMenuNode[]): WireMenuNode[] =>
  nodes.flatMap((node) => [node, ...flattenMenus(node.children ?? [])]);

describe('REQ20261127234158 JobView 视图 wire 对照（非 MSW）', () => {
  const cronRow = JSON.parse(JOB_VIEW_CRON_WIRE) as Record<string, unknown>;
  const fixedRateRow = JSON.parse(JOB_VIEW_FIXED_RATE_WIRE) as Record<string, unknown>;

  it('should_use_long_names_cron_expr_and_fixed_rate_ms_with_no_short_names', () => {
    for (const row of [cronRow, fixedRateRow]) {
      // 键名对照：长名在、短名不在（任一侧漂移即红）
      expect('cronExpr' in row, '视图键应为 cronExpr').toBe(true);
      expect('fixedRateMs' in row, '视图键应为 fixedRateMs').toBe(true);
      expect('cron' in row, '视图不得含短名 cron 键').toBe(false);
      expect('fixedRate' in row, '视图不得含短名 fixedRate 键').toBe(false);
      expect(JOB_VIEW_CRON_WIRE).not.toMatch(/"cron"\s*:/);
      expect(JOB_VIEW_FIXED_RATE_WIRE).not.toMatch(/"fixedRate"\s*:/);
    }
    // golden 片段键集 ⊆ JobAdminView 声明键集（片段非全量 DTO；FE 未声明键不入片段）
    const declaredKeys = Object.keys(jobViewFixture);
    for (const key of JOB_VIEW_WIRE_KEYS) {
      expect(declaredKeys, `JobAdminView 应声明 JobView wire 键 ${key}`).toContain(key);
    }
  });

  it('should_stringify_long_fields_and_keep_shard_total_numeric_in_view_wire', () => {
    // LongToStringModule 全局生效：id/version/fixedRateMs/timeoutMs 视图 wire 字符串；shardTotal 数值
    expect(cronRow.id).toBeTypeOf('string');
    expect(cronRow.version).toBeTypeOf('string');
    expect(cronRow.timeoutMs).toBeTypeOf('string');
    expect(fixedRateRow.fixedRateMs).toBeTypeOf('string');
    expect(fixedRateRow.id).toBeTypeOf('string');
    expect(fixedRateRow.version).toBeTypeOf('string');
    expect(fixedRateRow.timeoutMs).toBeTypeOf('string');
    expect(cronRow.shardTotal).toBeTypeOf('number');
    expect(fixedRateRow.shardTotal).toBeTypeOf('number');
    // cronExpr 为字符串或 null（两分支各锁一行）
    expect(cronRow.cronExpr).toBeTypeOf('string');
    expect(fixedRateRow.cronExpr).toBeNull();
    // FE 声明对照：fixture 同键同类型（satisfies 已锁编译期，此处锁运行期）
    expect(typeof jobViewFixture.id).toBe(typeof fixedRateRow.id);
    expect(typeof jobViewFixture.version).toBe(typeof fixedRateRow.version);
    expect(typeof jobViewFixture.timeoutMs).toBe(typeof fixedRateRow.timeoutMs);
    expect(typeof jobViewFixture.fixedRateMs).toBe(typeof fixedRateRow.fixedRateMs);
    expect(typeof jobViewFixture.shardTotal).toBe(typeof fixedRateRow.shardTotal);
  });

  it('should_exempt_registered_drift_keys_from_type_comparison_only', () => {
    // 已登记不改项（契约待确认项②）：param/retryPolicy 后端为 jsonb 文本 String、FE 声明 object——
    // 键名参与对照、类型对照显式豁免；golden 原文按后端真实形态（字符串）呈现
    expect(cronRow.param).toBeTypeOf('string');
    expect(cronRow.retryPolicy).toBeTypeOf('string');
    for (const key of REGISTERED_DRIFT_KEYS) {
      expect(Object.keys(jobViewFixture), `JobAdminView 仍声明登记不改键 ${key}`).toContain(key);
    }
  });
});

describe('REQ20261127234158 JobSaveRequest 载荷 wire 对照（非 MSW）', () => {
  it('should_submit_long_name_payload_keys_only_with_number_fixed_rate_ms', () => {
    const serialized = JSON.stringify(jobSavePayloadFixture);
    // AC-4：载荷字段为长名 cronExpr/fixedRateMs，不再出现 cron/fixedRate
    expect(serialized).toContain('"cronExpr"');
    expect(serialized).toContain('"fixedRateMs":300000');
    expect(serialized).not.toMatch(/"cron"\s*:/);
    expect(serialized).not.toMatch(/"fixedRate"\s*:/);
    // 载荷 fixedRateMs 发 number 合法（后端反序列化接受 number 与 string 双口径）
    expect(jobSavePayloadFixture.fixedRateMs).toBeTypeOf('number');
    expect(Object.keys(jobSavePayloadFixture)).not.toContain('cron');
    expect(Object.keys(jobSavePayloadFixture)).not.toContain('fixedRate');
    expect(Object.keys(jobSavePayloadFixture)).toContain('cronExpr');
    expect(Object.keys(jobSavePayloadFixture)).toContain('fixedRateMs');
  });
});

describe('REQ20261127234158 AdminMenuView 视图 wire 对照（非 MSW）', () => {
  const tree = JSON.parse(MENU_TREE_WIRE) as WireMenuNode[];
  const nodes = flattenMenus(tree);
  const root = tree[0]!;

  it('should_stringify_id_parent_id_version_and_root_parent_id_as_zero_string', () => {
    for (const node of nodes) {
      expect(node.id, 'AdminMenuView.id 应为 JSON 字符串').toBeTypeOf('string');
      expect(node.parentId, 'AdminMenuView.parentId 应为 JSON 字符串').toBeTypeOf('string');
      expect(node.version, 'AdminMenuView.version 应为 JSON 字符串').toBeTypeOf('string');
      // golden 片段键集 ⊆ AdminMenuView 声明键集
      for (const key of Object.keys(node)) {
        expect(Object.keys(menuFixture), `AdminMenuView 应声明 wire 键 ${key}`).toContain(key);
      }
    }
    // 根节点 parentId="0"（字符串零，非 null）
    expect(root.parentId, '根节点 parentId 应为字符串零').toBe('0');
    expect(root.parentId).not.toBeNull();
    expect(menuFixture.parentId).toBe('0');
  });

  it('should_carry_enum_names_and_scalar_types_per_menu_node', () => {
    for (const node of nodes) {
      expect(MENU_TYPES, `menuType 枚举名非法：${String(node.menuType)}`).toContain(node.menuType);
      expect(MENU_STATUSES, `status 枚举名非法：${String(node.status)}`).toContain(node.status);
      expect(node.sortOrder).toBeTypeOf('number');
      expect(node.visible).toBeTypeOf('boolean');
      expect(Array.isArray(node.children), 'children 恒为数组（嵌套保留）').toBe(true);
    }
    // 三枚举名全出现在 golden 树中（DIRECTORY/MENU/BUTTON 最小闭环）
    expect(nodes.map((node) => node.menuType).sort()).toEqual(['BUTTON', 'DIRECTORY', 'MENU']);
  });
});
