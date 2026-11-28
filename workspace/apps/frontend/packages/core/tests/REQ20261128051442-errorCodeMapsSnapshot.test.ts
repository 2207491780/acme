import { describe, expect, it } from 'vitest';
import { WORKFLOW_ERROR_CODES } from '../src/api/workflow';
import { DIRECT_UPLOAD_ERROR_I18N_KEYS, TRANSFER_ERROR_MESSAGES } from '../src/file-transfer';
import { NOTIFY_ERROR_MESSAGES } from '../src/notify-errors';
import { SYSTEM_ERROR_I18N_KEYS } from '../src/system-errors';
import { zhCN } from '../src/i18n/resources/zh-CN';

/**
 * REQ-20261128-051442（wire 契约·错误码统一）AC-2/AC-3/AC-10：前端映射表快照测试。
 *
 * 口径（契约确认⑤）：契约附录 A 为唯一权威清单——本文件对四张前端映射表做**显式逐键静态对照**
 * （键集与键值 == 附录 A 对应域切片），任一侧漂移即红；禁 MSW 假绿（不以 MSW 行为替代静态对照，
 * 本文件不 import 任何 mocks 模块）。
 *
 * 覆盖切片：
 *   - WORKFLOW_ERROR_CODES        == 附录 A.5（platform-workflow 01/02/03 三段全 14 码，AC-2）；
 *   - SYSTEM_ERROR_I18N_KEYS      == 附录 A.3/A.4 切片（含 auth 04 段跨域归位；i18n key 值与文案不变，AC-3）；
 *   - NOTIFY_ERROR_MESSAGES       == 附录 A.3 切片（platform-system 08/09 段；文案不变，AC-3）；
 *   - TRANSFER_ERROR_MESSAGES + DIRECT_UPLOAD_ERROR_I18N_KEYS == 附录 A.7 切片（infra 裸字面量口径，
 *     AC-10；PRESIGN_UNSUPPORTED 为 FE 内部态键单独登记豁免——非后端发射，不属附录 A，见确认⑧）。
 */

/** 按点分路径解析 zh-CN 资源树文案（仅静态对照用；缺路径即测试红）。 */
function zhText(key: string): string {
  let node: unknown = zhCN;
  for (const seg of key.split('.')) {
    if (node === null || typeof node !== 'object' || !(seg in (node as Record<string, unknown>))) {
      throw new Error(`zh-CN 资源缺失键：${key}`);
    }
    node = (node as Record<string, unknown>)[seg];
  }
  if (typeof node !== 'string') {
    throw new Error(`zh-CN 资源键非文案叶子：${key}`);
  }
  return node;
}

describe('REQ20261128051442 前端映射表快照（附录 A 静态对照，AC-2/3/10）', () => {
  it('should_match_appendix_a5_when_snapshotting_WORKFLOW_ERROR_CODES', () => {
    // AC-2：键集覆盖 01/02/03 三段语义键、每个键值 == 附录 A.5 全码（既有 5 键名兼容保留 + 9 语义键）
    expect(WORKFLOW_ERROR_CODES).toEqual({
      // —— 01 流程定义 ——
      KEY_DUPLICATE: 'platform-workflow-01-0001',
      XML_INVALID: 'platform-workflow-01-0002',
      VALIDATION_FAILED: 'platform-workflow-01-0003',
      NOT_FOUND: 'platform-workflow-01-0004',
      DISABLED: 'platform-workflow-01-0005',
      // —— 02 流程实例 ——
      INSTANCE_NOT_FOUND: 'platform-workflow-02-0001',
      INSTANCE_STATE_CONFLICT: 'platform-workflow-02-0002',
      DEFINITION_NOT_DEPLOYED: 'platform-workflow-02-0003',
      BUSINESS_KEY_REQUIRED: 'platform-workflow-02-0004',
      // —— 03 任务 ——
      TASK_NOT_FOUND: 'platform-workflow-03-0001',
      TASK_STATE_CONFLICT: 'platform-workflow-03-0002',
      ASSIGNEE_MISMATCH: 'platform-workflow-03-0003',
      DELEGATE_STATE_INVALID: 'platform-workflow-03-0004',
      NO_ASSIGNEE_RESOLVED: 'platform-workflow-03-0005',
    });
    // 无短码残留（'01-0001' 形态禁止存在）：全部为三段全码形态
    const values = Object.values(WORKFLOW_ERROR_CODES);
    expect(values).toHaveLength(14);
    for (const value of values) {
      expect(value).toMatch(/^platform-workflow-(01|02|03)-\d{4}$/);
    }
  });

  it('should_match_appendix_a3_a4_when_snapshotting_SYSTEM_ERROR_I18N_KEYS', () => {
    // AC-3：键值全码化（含 SESSION/REVOKE 跨域归位 platform-auth-04 段）
    expect(SYSTEM_ERROR_I18N_KEYS).toEqual({
      'platform-system-10-0002': 'depts.errors.deptHasChildren',
      'platform-system-10-0003': 'depts.errors.deptHasUsers',
      'platform-system-11-0002': 'positions.errors.positionInUse',
      'platform-system-02-0008': 'roles.scope.invalid',
      'platform-auth-04-0001': 'onlineUsers.errors.sessionNotFound',
      'platform-auth-04-0002': 'onlineUsers.errors.revokeSuperadminForbidden',
    });
    // i18n key 值不变 + 文案不变（zh-CN 资源树静态解析）
    expect(zhText('depts.errors.deptHasChildren')).toBe('存在子部门，无法删除');
    expect(zhText('depts.errors.deptHasUsers')).toBe('部门下存在用户，无法删除');
    expect(zhText('positions.errors.positionInUse')).toBe('岗位已被用户引用，无法删除');
    expect(zhText('roles.scope.invalid')).toBe('数据范围参数无效');
    expect(zhText('onlineUsers.errors.sessionNotFound')).toBe('目标会话不存在（可能已注销或过期）');
    expect(zhText('onlineUsers.errors.revokeSuperadminForbidden')).toBe('超级管理员会话不可被非超管踢下线');
  });

  it('should_match_appendix_a3_when_snapshotting_NOTIFY_ERROR_MESSAGES', () => {
    // AC-3：键值全码化（platform-system 08/09 段）且消息文案不变
    expect(NOTIFY_ERROR_MESSAGES).toEqual({
      'platform-system-08-0001': '公告已发布，需先下线后再操作',
      'platform-system-08-0002': '公告不存在或已被删除',
      'platform-system-09-0002': '无权操作该站内信',
      'platform-system-09-0001': '站内信不存在或已被删除',
    });
  });

  it('should_match_appendix_a7_when_snapshotting_TRANSFER_ERROR_MESSAGES', () => {
    // AC-10：infra 裸字面量 wire 真值（附录 A.7）逐键对照 + 文案不变
    expect(TRANSFER_ERROR_MESSAGES).toEqual({
      FILE_SIZE_EXCEEDED: '文件大小超出限制',
      FILE_TYPE_REJECTED: '文件类型不被允许',
      EXCEL_PARSE_FAILED: 'Excel 文件解析失败，请确认文件为合法的 xlsx 格式',
      FILE_NOT_FOUND: '文件不存在或已被删除',
    });
  });

  it('should_match_appendix_a7_bare_literals_when_snapshotting_DIRECT_UPLOAD_ERROR_I18N_KEYS', () => {
    // AC-10/确认⑧：收敛裸码单口径——键集 == 附录 A.7 裸字面量切片（STORAGE_QUOTA_EXCEEDED/
    // STORAGE_OBJECT_MISSING 本身即裸字面量真值）+ i18n key 值不变
    expect(DIRECT_UPLOAD_ERROR_I18N_KEYS).toEqual({
      STORAGE_QUOTA_EXCEEDED: 'files.errors.quotaExceeded',
      IMAGE_INVALID: 'files.errors.imageInvalid',
      TICKET_INVALID: 'files.errors.ticketInvalid',
      SIZE_MISMATCH: 'files.errors.sizeMismatch',
      STORAGE_OBJECT_MISSING: 'files.errors.objectMissing',
      PRESIGN_UNSUPPORTED: 'files.errors.presignUnsupported',
    });
    expect(zhText('files.errors.quotaExceeded')).toBe('租户存储配额已超限，请清理文件或联系管理员调整配额');
    // 与 wire 真值不符的三键确已删除（防双口径回归）
    expect('QUOTA_EXCEEDED' in DIRECT_UPLOAD_ERROR_I18N_KEYS).toBe(false);
    expect('STORAGE_IMAGE_INVALID' in DIRECT_UPLOAD_ERROR_I18N_KEYS).toBe(false);
    expect('STORAGE_TICKET_INVALID' in DIRECT_UPLOAD_ERROR_I18N_KEYS).toBe(false);
    // 豁免登记：PRESIGN_UNSUPPORTED 为 FE 内部态键（非后端发射，不属附录 A，确认⑧）
    expect(DIRECT_UPLOAD_ERROR_I18N_KEYS.PRESIGN_UNSUPPORTED).toBe('files.errors.presignUnsupported');
    expect(zhText('files.errors.presignUnsupported')).toBe('当前存储类型不支持直传，请联系管理员');
  });
});
