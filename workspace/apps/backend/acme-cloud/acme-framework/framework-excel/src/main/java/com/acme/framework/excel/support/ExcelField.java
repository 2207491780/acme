package com.acme.framework.excel.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Excel 字段映射声明（FR-004/005、BR-010：映射声明非法/缺失显式失败 SYS-12-0001）。
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ExcelField {

    /** 列头名。 */
    String value();

    /** 列序（小在前，缺省按声明序）。 */
    int order() default Integer.MAX_VALUE;
}
