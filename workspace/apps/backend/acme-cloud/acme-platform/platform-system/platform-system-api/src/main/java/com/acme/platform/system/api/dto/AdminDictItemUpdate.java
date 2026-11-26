package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 字典项修改请求（version=乐观锁版本；REQ-20261125-230807 增补 parentId——全值语义：
 * 提交值即新父（null=顶层 / 清父），仅 TREE 类型可携非空，FLAT 类型提交非空被用例拒绝）。
 */
public record AdminDictItemUpdate(@NotBlank @Size(min = 1, max = 128) String itemLabel,
                                  @NotBlank @Size(min = 1, max = 128) String itemValue,
                                  @NotNull @Min(0) @Max(9999) Integer sortOrder,
                                  @NotNull Long version,
                                  Long parentId) {

    /** 兼容构造（REQ-20261125-230807 之前调用面）：parentId 缺省 null=顶层（FLAT 现行为）。 */
    public AdminDictItemUpdate(String itemLabel, String itemValue, Integer sortOrder, Long version) {
        this(itemLabel, itemValue, sortOrder, version, null);
    }
}
