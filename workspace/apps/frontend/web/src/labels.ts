/**
 * 分类标签 i18n key 映射（REQ-20261114-120103；REQ-20261126-125836 迁移）：
 * 状态/平台/环境/健康标签统一从 `common` 域解析（原 releases 域已随演示功能删除），
 * zh-CN / en-US 双资源包同键，与 @acme/core 的分类色令牌同源。
 */

export const statusLabelKey = (s: string): string => `common.status.${s}`;

export const platformLabelKey = (p: string): string => `common.platformFull.${p}`;

export const platformShortKey = (p: string): string => `common.platform.${p}`;

export const envLabelKey = (e: string): string => `common.env.${e}`;

export const healthLabelKey = (h: string): string => `common.health.${h}`;
