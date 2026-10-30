package com.acme.common.validation.internal;

import com.acme.common.validation.InEnum;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.HashSet;
import java.util.Set;

/**
 * {@link InEnum} 校验器（FR-029；DP-013 定稿）：被校验值属于 enumClass 常量集则通过；
 * null 视为通过（存在性校验交 {@code @NotNull}，惯例口径）；约束参数非法
 * （enumClass 缺失）时初始化显式报错（FR-029 异常分支）。
 * 常量集在 initialize 期构建哈希索引，isValid O(1) 判定、热路径零数组分配。
 */
public final class InEnumValidator implements ConstraintValidator<InEnum, Object> {

    private Set<Object> constants;

    @Override
    public void initialize(InEnum annotation) {
        Class<? extends Enum<?>> target = annotation.enumClass();
        if (target == null) {
            throw new IllegalArgumentException("enumClass 不能为空");
        }
        Enum<?>[] values = target.getEnumConstants();
        if (values == null) {
            throw new IllegalArgumentException("enumClass 不是枚举类型：" + target.getName());
        }
        this.constants = new HashSet<>(values.length);
        for (Enum<?> value : values) {
            constants.add(value);
        }
    }

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return constants.contains(value);
    }
}
