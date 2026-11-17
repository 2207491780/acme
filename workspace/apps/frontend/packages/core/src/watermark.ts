import { formatDateOnly, formatTimeOnly, type DateInput } from './date-format';

/**
 * 水印模板渲染器（REQ-20261117-180000）：租户经 sys_config.watermark_template 自定义水印内容。
 * 占位符集与后端 SystemConfigUseCase.WATERMARK_PLACEHOLDERS 对齐；
 * null/空模板 → 仅用户名（沿用 REQ-20261116-230354 现状）；
 * {date}/{time} 走统一日期 formatter（随语言/时区偏好），调用方按分钟刷新 now 驱动 {time} 更新。
 */

/** 合法占位符（username 登录账号 / nickname 昵称 / userId 用户ID / tenantCode 租户代码 / deptName 部门 / date 当前日期 / time 当前时间）。 */
export const WATERMARK_PLACEHOLDERS = [
  'username',
  'nickname',
  'userId',
  'tenantCode',
  'deptName',
  'date',
  'time',
] as const;

export type WatermarkPlaceholder = (typeof WATERMARK_PLACEHOLDERS)[number];

/** 模板长度上限（对齐 sys_config.watermark_template varchar(255) 与 DTO @Size）。 */
export const WATERMARK_TEMPLATE_MAX_LENGTH = 255;

/** 渲染上下文（会话身份 + 组织字段；除 username 外均可缺省）。 */
export interface WatermarkContext {
  username: string;
  nickname?: string | null;
  userId?: string | null;
  tenantCode?: string | null;
  deptName?: string | null;
}

const TOKEN_PATTERN = /\{([^{}]*)\}/g;

export function isSupportedWatermarkPlaceholder(value: unknown): value is WatermarkPlaceholder {
  return typeof value === 'string' && (WATERMARK_PLACEHOLDERS as readonly string[]).includes(value);
}

/** 模板中未知的完整占位符清单（空数组=合法；与后端拒绝口径一致，供配置页保存前校验）。 */
export function findUnknownWatermarkPlaceholders(template: string): string[] {
  const unknown: string[] = [];
  for (const match of template.matchAll(TOKEN_PATTERN)) {
    if (!isSupportedWatermarkPlaceholder(match[1])) {
      unknown.push(match[1]);
    }
  }
  return unknown;
}

/** 渲染水印内容：未知/残缺占位符按字面量保留（防御口径；保存侧已拒绝未知完整 token）。 */
export function renderWatermarkTemplate(
  template: string | null | undefined,
  context: WatermarkContext,
  now: DateInput = new Date(),
): string {
  if (!template) {
    return context.username;
  }
  return template.replace(TOKEN_PATTERN, (literal, token: string) => {
    switch (token) {
      case 'username':
        return context.username;
      case 'nickname':
        return context.nickname || context.username;
      case 'userId':
        return context.userId ?? '';
      case 'tenantCode':
        return context.tenantCode ?? '';
      case 'deptName':
        return context.deptName ?? '';
      case 'date':
        return formatDateOnly(now);
      case 'time':
        return formatTimeOnly(now);
      default:
        return literal;
    }
  });
}