package com.acme.platform.system.api.dto;

import com.acme.platform.system.api.EnableStatus;

/**
 * 服务能力目录视图（REQ-20261121-054613 确认③④）：GET /admin/v1/system/capabilities 行形态，
 * 仅返回 status=ENABLED 能力（id asc），套餐表单「能力」checkbox 组数据源。
 *
 * @param id             能力 ID（wire 字符串化）
 * @param capabilityCode 能力码（全局唯一，如 platform:workflow / platform:scheduler）
 * @param capabilityName 能力名（展示用）
 * @param status         启停状态（目录恒 ENABLED，wire 枚举名）
 */
public record AdminCapabilityView(Long id, String capabilityCode, String capabilityName, EnableStatus status) {
}
