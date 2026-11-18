import type { Env, Health, Platform, Status } from '@acme/core';

/**
 * 分类标签 i18n key 映射（REQ-20261114-120103）：
 * 与 @acme/core 常量（statusLabel/platformLabel/envLabel/HEALTH_LABELS）同源同值，
 * Web 层经资源包渲染支持语言切换；mobile 仍直接消费 core 常量（文案迁移为后续 REQ）。
 */

export const statusLabelKey = (s: Status | string): string => `releases.status.${s}`;

export const platformLabelKey = (p: Platform | string): string => `releases.platformFull.${p}`;

export const platformShortKey = (p: Platform | string): string => `releases.platform.${p}`;

export const envLabelKey = (e: Env | string): string => `releases.env.${e}`;

export const healthLabelKey = (h: Health | string): string => `releases.health.${h}`;
