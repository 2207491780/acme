package com.acme.platform.system.api.dto;

import com.acme.platform.system.api.EnableStatus;

import java.time.Instant;
import java.util.List;

/**
 * 用户视图（11.5.1 Admin* 前缀；不含任何凭据形态字段，FR-005 / 11.5.2）。
 * id / version / roleIds / deptId / positionId 中 Long 经 common-serialization 序列化为字符串。
 * REQ-20261114-120104 增补 deptId / positionId（可空）。
 */
public record AdminUserView(Long id, String username, String nickname, String email, String phone,
                            Long deptId, Long positionId,
                            EnableStatus status, Boolean builtIn, List<Long> roleIds,
                            Instant createdAt, Instant updatedAt, Long version) {

    public AdminUserView {
        roleIds = roleIds == null ? List.of() : List.copyOf(roleIds);
    }
}
