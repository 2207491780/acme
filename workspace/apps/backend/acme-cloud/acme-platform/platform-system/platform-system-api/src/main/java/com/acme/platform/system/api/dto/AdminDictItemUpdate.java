package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 字典项修改请求（version=乐观锁版本）。
 */
public record AdminDictItemUpdate(@NotBlank @Size(min = 1, max = 128) String itemLabel,
                                  @NotBlank @Size(min = 1, max = 128) String itemValue,
                                  @NotNull @Min(0) @Max(9999) Integer sortOrder,
                                  @NotNull Long version) {
}
