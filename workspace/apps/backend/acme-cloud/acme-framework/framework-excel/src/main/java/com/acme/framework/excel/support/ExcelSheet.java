package com.acme.framework.excel.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Excel 工作表映射声明（FR-004/005、BR-010：业务字段映射归业务侧，framework 无任何业务字段映射）。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ExcelSheet {

    /** 工作表名（缺省取类型名）。 */
    String value() default "";
}
