package com.acme.platform.system.api.dto;

import com.acme.platform.system.api.EnableStatus;

import java.time.Instant;

/**
 * 岗位视图（REQ-20261114-120104 契约⑫钉死形状 id/positionCode/positionName/status/createdAt/version）。
 */
public record AdminPositionView(Long id, String positionCode, String positionName, EnableStatus status,
                                Instant createdAt, Long version) {
}
