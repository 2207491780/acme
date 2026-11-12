package com.acme.common.validation;

import com.acme.common.validation.internal.InEnumValidator;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 枚举取值约束（FR-029；DP-013 定稿为正式约束）：被校验值属于 {@link #enumClass()} 常量集则通过。
 * null 口径：null 视为通过（Bean Validation 惯例），存在性校验交 {@code @NotNull} 等独立约束。
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = InEnumValidator.class)
public @interface InEnum {

    /** 目标枚举类（FR-029）。 */
    Class<? extends Enum<?>> enumClass();

    /** 失败信息键（i18n 口径见 DP-012）。 */
    String message() default "validation.inEnum";

    Class<?>[] groups() default {};

    Class<?>[] payload() default {};
}
