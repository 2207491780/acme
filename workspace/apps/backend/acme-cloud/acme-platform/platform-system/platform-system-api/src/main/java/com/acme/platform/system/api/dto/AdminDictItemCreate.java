package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 字典项创建请求（挂指定类型下；REQ-20261125-230807 增补 parentId——仅 TREE 类型可携，
 * FLAT 类型提交非空被用例以 VALIDATION_FAILED 400 拒绝；null=顶层）。
 */
public record AdminDictItemCreate(@NotBlank @Size(min = 1, max = 128) String itemLabel,
                                  @NotBlank @Size(min = 1, max = 128) String itemValue,
                                  @NotNull @Min(0) @Max(9999) Integer sortOrder,
                                  Long parentId) {

    /** 兼容构造（REQ-20261125-230807 之前调用面）：parentId 缺省 null=顶层（FLAT 现行为）。 */
    public AdminDictItemCreate(String itemLabel, String itemValue, Integer sortOrder) {
        this(itemLabel, itemValue, sortOrder, null);
    }
}
