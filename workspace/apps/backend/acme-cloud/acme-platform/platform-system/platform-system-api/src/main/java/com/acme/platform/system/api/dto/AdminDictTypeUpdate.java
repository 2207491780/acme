package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 字典类型修改请求（dictCode 不可改；version=乐观锁版本）。
 */
public record AdminDictTypeUpdate(@NotBlank @Size(min = 1, max = 64) String dictName,
                                  @Size(max = 255) String remark,
                                  @NotNull Long version) {
}
