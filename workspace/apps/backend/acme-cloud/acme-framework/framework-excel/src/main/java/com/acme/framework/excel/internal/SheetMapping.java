package com.acme.framework.excel.internal;

import com.acme.common.core.BizException;
import com.acme.framework.excel.support.ExcelError;
import com.acme.framework.excel.support.ExcelField;
import com.acme.framework.excel.support.ExcelSheet;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 注解 → 列定义解析（FR-004/005、BR-010：业务字段映射归业务侧声明）。
 * 映射声明非法/缺失显式失败（SYS-12-0001，架构设计 6.2 不产出半截文件）。
 */
public final class SheetMapping {

    private SheetMapping() {
    }

    /** 列定义（列头 + 读写取值器）。 */
    public record Column(String header, Getter getter, Setter setter) {

        /** 取值（导出）。 */
        public Object read(Object record) {
            return getter.get(record);
        }

        /** 赋值（导入）。 */
        public void write(Object record, Object value) {
            if (setter == null) {
                throw new IllegalStateException("列不可写：" + header);
            }
            setter.set(record, value);
        }
    }

    /** 字段取值器。 */
    @FunctionalInterface
    public interface Getter {
        Object get(Object record);
    }

    /** 字段赋值器。 */
    @FunctionalInterface
    public interface Setter {
        void set(Object record, Object value);
    }

    /** 工作表映射（名称 + 列）。 */
    public record Mapping(String sheetName, List<Column> columns) {

        public List<String> headers() {
            return columns.stream().map(Column::header).toList();
        }
    }

    /** 解析类型映射：工作表名 + 列定义（按 order 稳定排序）。 */
    public static Mapping resolve(Class<?> type) {
        if (type == null) {
            throw new BizException(ExcelError.MAPPING_INVALID);
        }
        ExcelSheet sheet = type.getAnnotation(ExcelSheet.class);
        String sheetName = sheet == null || sheet.value().isBlank() ? type.getSimpleName() : sheet.value();
        List<Annotated> annotated = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            ExcelField annotation = field.getAnnotation(ExcelField.class);
            if (annotation == null) {
                continue;
            }
            field.setAccessible(true);
            annotated.add(new Annotated(annotation.value(), annotation.order(), annotated.size(),
                    record -> {
                        try {
                            return field.get(record);
                        } catch (IllegalAccessException ex) {
                            throw new IllegalStateException("字段读取失败", ex);
                        }
                    }, (record, value) -> {
                        try {
                            field.set(record, convert(value, field.getType()));
                        } catch (IllegalAccessException ex) {
                            throw new IllegalStateException("字段写入失败", ex);
                        }
                    }));
        }
        for (Method method : type.getDeclaredMethods()) {
            ExcelField annotation = method.getAnnotation(ExcelField.class);
            if (annotation == null || method.getParameterCount() != 0) {
                continue;
            }
            method.setAccessible(true);
            annotated.add(new Annotated(annotation.value(), annotation.order(), annotated.size(),
                    record -> {
                        try {
                            return method.invoke(record);
                        } catch (ReflectiveOperationException ex) {
                            throw new IllegalStateException("属性读取失败", ex);
                        }
                    }, null));
        }
        if (annotated.isEmpty()) {
            throw new BizException(ExcelError.MAPPING_INVALID);
        }
        annotated.sort(Comparator.comparingInt(Annotated::order).thenComparingInt(Annotated::declarationIndex));
        List<Column> columns = annotated.stream()
                .map(a -> new Column(a.header(), a.getter(), a.setter()))
                .toList();
        return new Mapping(sheetName, columns);
    }

    /** 声明中间态（order + 声明序）。 */
    private record Annotated(String header, int order, int declarationIndex, Getter getter, Setter setter) {
    }

    /** 文本 → 目标类型（导入基础转换）。 */
    static Object convert(Object value, Class<?> targetType) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        if (targetType == String.class) {
            return text;
        }
        if (targetType == Integer.class || targetType == int.class) {
            return Integer.valueOf(text);
        }
        if (targetType == Long.class || targetType == long.class) {
            return Long.valueOf(text);
        }
        if (targetType == Double.class || targetType == double.class) {
            return Double.valueOf(text);
        }
        if (targetType == java.math.BigDecimal.class) {
            return new java.math.BigDecimal(text);
        }
        if (targetType == Boolean.class || targetType == boolean.class) {
            return Boolean.valueOf(text);
        }
        if (targetType == java.time.LocalDate.class) {
            return java.time.LocalDate.parse(text);
        }
        return text;
    }
}
