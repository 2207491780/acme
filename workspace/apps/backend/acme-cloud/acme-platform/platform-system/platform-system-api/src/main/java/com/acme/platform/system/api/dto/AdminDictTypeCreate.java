package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 字典类型创建请求。
 */
public record AdminDictTypeCreate(@NotBlank @Pattern(regexp = "^[a-z][a-z0-9_-]*$") @Size(min = 2, max = 64) String dictCode,
                                  @NotBlank @Size(min = 1, max = 64) String dictName,
                                  @Size(max = 255) String remark) {
}
