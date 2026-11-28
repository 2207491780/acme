/**
 * REQ-20261127-234158（wire 契约·DTO 字段对齐 P1-06）：C 端 AppTokenIssued wire 对照测试。
 *
 * 【事实源=backend REQ20261127234158*WireTest.java，任一侧漂移即红】
 * 本文件为非 MSW 纯数据对照测试（禁 fetch、禁 MSW）：golden 片段与 Java 真实序列化产物
 * （JsonModules.standard() mapper writeValueAsString）字节等价，逐键断言 mobile
 * `AppTokenIssued` TS 接口的键名与类型与 wire 一致——`satisfies` 编译期锁定 + 运行期键集断言。
 *
 * 钉死 wire golden（后端序列化为权威，禁反向发明）：
 *   - wire 键：accessToken/refreshToken/accessExpiresAt/refreshExpiresAt/userId/phone；
 *   - 两个过期时间为 ISO-8601 UTC 字符串；C 端 wire 不存在 `expiresAt` 键；
 *   - `userId` 为 JSON 字符串（LongToStringModule，如 "1234567890123456789" 带引号）。
 */
import { describe, expect, it } from 'vitest';
import type { AppTokenIssued } from '../src/api/auth';

/** wire golden 片段（与 platform-auth-api AppTokenIssued 真实序列化产物字节等价；
 *  事实源=backend REQ20261127234158*WireTest.java 的 AppTokenIssued 序列化用例）。 */
const TOKEN_WIRE = [
  '{',
  '  "accessToken": "at-golden-20261127234158",',
  '  "refreshToken": "rt-golden-20261127234158",',
  '  "accessExpiresAt": "2026-10-26T10:00:00Z",',
  '  "refreshExpiresAt": "2026-11-09T10:00:00Z",',
  '  "userId": "1234567890123456789",',
  '  "phone": "13800138000"',
  '}',
].join('\n');

/** wire 键集（契约钉死事实①原文顺序无关）。 */
const WIRE_KEYS = ['accessToken', 'refreshToken', 'accessExpiresAt', 'refreshExpiresAt', 'userId', 'phone'] as const;

/** FE 侧对照 fixture（satisfies 编译期锁定 AppTokenIssued 键名/类型；值与 golden 同源）。 */
const tokenFixture = {
  accessToken: 'at-golden-20261127234158',
  refreshToken: 'rt-golden-20261127234158',
  accessExpiresAt: '2026-10-26T10:00:00Z',
  refreshExpiresAt: '2026-11-09T10:00:00Z',
  userId: '1234567890123456789',
  phone: '13800138000',
} satisfies AppTokenIssued;

describe('REQ20261127234158 C 端 AppTokenIssued wire 对照（非 MSW）', () => {
  const wire = JSON.parse(TOKEN_WIRE) as Record<string, unknown>;

  it('should_match_wire_key_set_with_ts_interface_keys_exactly', () => {
    // golden 键集与 TS 接口键集全等（多键/少键/改名任一漂移即红）
    expect(Object.keys(wire).sort()).toEqual([...WIRE_KEYS].sort());
    expect(Object.keys(tokenFixture).sort()).toEqual([...WIRE_KEYS].sort());
    // C 端 wire 不存在 expiresAt 键（P1-06 漂移根因；B 端 AdminTokenIssued.expiresAt 为正确契约不在此列）
    expect('expiresAt' in wire, 'C 端 wire 不得含 expiresAt 键').toBe(false);
    expect(TOKEN_WIRE).not.toMatch(/"expiresAt"\s*:/);
  });

  it('should_match_wire_field_types_with_ts_declarations_per_key', () => {
    // 逐键类型对照：wire 值类型 === TS 声明值类型（全 string，含 userId）
    for (const key of WIRE_KEYS) {
      expect(typeof wire[key], `wire.${key} 类型应与 AppTokenIssued.${key} 声明一致`)
        .toBe(typeof tokenFixture[key]);
      expect(typeof tokenFixture[key], `AppTokenIssued.${key} 声明应为 string`).toBe('string');
    }
  });

  it('should_carry_stringified_snowflake_user_id_not_json_number', () => {
    // userId 为 JSON 字符串（带引号的数字串；数值化会超 Number.MAX_SAFE_INTEGER 丢精度）
    expect(wire.userId).toBeTypeOf('string');
    expect(TOKEN_WIRE, 'golden 原文中 userId 值必须带引号（JSON 字符串）')
      .toContain('"userId": "1234567890123456789"');
    expect(/^\d+$/.test(tokenFixture.userId)).toBe(true);
    expect(Number(tokenFixture.userId)).toBeGreaterThan(Number.MAX_SAFE_INTEGER);
  });

  it('should_carry_iso8601_utc_expiry_strings_parseable_to_finite_ms', () => {
    // 两个过期时间为 ISO-8601 UTC 字符串（golden 片段锁名与形态）；Date.parse 为有限正数（AC-1 语义）
    expect(TOKEN_WIRE).toContain('"accessExpiresAt": "2026-10-26T10:00:00Z"');
    expect(TOKEN_WIRE).toContain('"refreshExpiresAt": "2026-11-09T10:00:00Z"');
    const accessMs = Date.parse(tokenFixture.accessExpiresAt);
    const refreshMs = Date.parse(tokenFixture.refreshExpiresAt);
    expect(Number.isFinite(accessMs) && accessMs > 0).toBe(true);
    expect(Number.isFinite(refreshMs) && refreshMs > 0).toBe(true);
    expect(accessMs, 'refresh 晚于 access（双令牌轮换语义）').toBeLessThan(refreshMs);
  });
});
