package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 角色修改请求（roleCode 不可改；version=乐观锁版本；parentId=null 不调整、0 移为根角色、
 * >0 调整父角色——内置角色拒绝，目标父不得为自身或其子孙，父角色须存在）。
 * REQ-20261125-230807：前端「清空父级」统一提交哨兵 0（AC-10，三元 falsy 吞 0 双层失效收口）。
 * 响应侧根角色 wire parentId 归一 null（对齐 REQ-20261124-213008 §API，见 AdminRoleView）。
 */
public record AdminRoleUpdate(@NotBlank @Size(min = 1, max = 64) String roleName,
                              @Size(max = 255) String remark,
                              Long parentId,
                              @NotNull Long version) {
}
