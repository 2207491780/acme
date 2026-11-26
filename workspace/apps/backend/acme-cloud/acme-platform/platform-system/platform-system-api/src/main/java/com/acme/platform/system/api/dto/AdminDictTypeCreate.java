package com.acme.platform.system.api.dto;

import com.acme.platform.system.api.DictKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 字典类型创建请求（REQ-20261125-230807 增补 dictKind：null 缺省 FLAT；修改接口不提供 kind 变更，
 * 形态创建时定）。
 */
public record AdminDictTypeCreate(@NotBlank @Pattern(regexp = "^[a-z][a-z0-9_-]*$") @Size(min = 2, max = 64) String dictCode,
                                  @NotBlank @Size(min = 1, max = 64) String dictName,
                                  @Size(max = 255) String remark,
                                  DictKind dictKind) {

    /** 兼容构造（REQ-20261125-230807 之前调用面）：dictKind 缺省 null=FLAT（平铺现状）。 */
    public AdminDictTypeCreate(String dictCode, String dictName, String remark) {
        this(dictCode, dictName, remark, null);
    }
}
