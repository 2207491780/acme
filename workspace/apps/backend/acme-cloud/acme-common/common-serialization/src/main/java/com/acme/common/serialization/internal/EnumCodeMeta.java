package com.acme.common.serialization.internal;

import com.fasterxml.jackson.annotation.JsonValue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 枚举 code 元数据一次解析缓存（FR-024、DQ-005）：热路径零反射扫描。
 * 识别顺序保持 DQ-005：序列化 {@code @JsonValue} &gt; {@code code()} &gt; {@code name()}；
 * 反序列化 {@code @JsonValue} 匹配 &gt; {@code fromCode} &gt; {@code code()} 匹配 &gt; {@code name()} 匹配。
 * 实现细节（Modules.md 2.3：模块外不得引用）。
 */
final class EnumCodeMeta {

    private static final ClassValue<EnumCodeMeta> CACHE = new ClassValue<>() {
        @Override
        protected EnumCodeMeta computeValue(Class<?> type) {
            return new EnumCodeMeta(type);
        }
    };

    private final Method jsonValueMethod;
    private final Field jsonValueField;
    private final Method codeMethod;
    private final Method fromCodeMethod;
    /** 枚举常量（仅枚举基类解析到；带类体常量的子类为 null）；其余两数组与之对齐。 */
    private final Object[] constants;
    private final Object[] jsonValueCodes;
    private final Object[] codeValues;

    private EnumCodeMeta(Class<?> type) {
        this.jsonValueMethod = findJsonValueMethod(type);
        this.jsonValueField = jsonValueMethod == null ? findJsonValueField(type) : null;
        this.codeMethod = findCodeMethod(type);
        this.fromCodeMethod = findFromCode(type);
        Class<?> enumBase = resolveEnumBase(type);
        if (enumBase != null && enumBase == type) {
            this.constants = type.getEnumConstants();
        } else {
            this.constants = null;
        }
        if (constants != null) {
            this.jsonValueCodes = new Object[constants.length];
            this.codeValues = codeMethod == null ? null : new Object[constants.length];
            for (int i = 0; i < constants.length; i++) {
                Object constant = constants[i];
                jsonValueCodes[i] = readJsonValue(constant, jsonValueMethod, jsonValueField);
                if (codeValues != null) {
                    codeValues[i] = invokeQuietly(codeMethod, constant);
                }
            }
        } else {
            this.jsonValueCodes = null;
            this.codeValues = null;
        }
    }

    static EnumCodeMeta of(Class<?> type) {
        return CACHE.get(type);
    }

    /** 序列化编码（DQ-005）：@JsonValue &gt; code() &gt; name()。 */
    Object encode(Object value) {
        Object fromJsonValue = readJsonValue(value, jsonValueMethod, jsonValueField);
        if (fromJsonValue != null) {
            return fromJsonValue;
        }
        if (codeMethod != null) {
            return invokeQuietly(codeMethod, value);
        }
        return ((Enum<?>) value).name();
    }

    /** @JsonValue 输出匹配（反序列化识别顺序首位，DQ-005）。 */
    Object matchByJsonValue(Object input) {
        return match(input, jsonValueCodes);
    }

    /** {@code code()} 返回值匹配（DQ-005：code() &gt; name()）。 */
    Object matchByCode(Object input) {
        return match(input, codeValues);
    }

    /** {@code fromCode} 静态方法约定（与 YesNo/Enabled 同款）；无法转换输入时返回 null 走后续兜底。 */
    Object invokeFromCode(Object input) {
        if (fromCodeMethod == null) {
            return null;
        }
        Object arg = convertArgument(input, fromCodeMethod.getParameterTypes()[0]);
        if (arg == null) {
            return null;
        }
        try {
            return fromCodeMethod.invoke(null, arg);
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            throw new IllegalArgumentException("未知枚举编码（FR-024）：" + input, e);
        }
    }

    /** {@code name()} 兜底（DQ-005）。 */
    Object matchByName(Object input) {
        if (!(input instanceof String text) || constants == null) {
            return null;
        }
        for (Object constant : constants) {
            if (((Enum<?>) constant).name().equals(text)) {
                return constant;
            }
        }
        return null;
    }

    private Object match(Object input, Object[] codes) {
        if (codes == null || input == null) {
            return null;
        }
        for (int i = 0; i < codes.length; i++) {
            if (codes[i] != null && codeMatches(codes[i], input)) {
                return constants[i];
            }
        }
        return null;
    }

    private static boolean codeMatches(Object code, Object input) {
        if (code instanceof Number codeNumber && input instanceof Number inputNumber) {
            return codeNumber.longValue() == inputNumber.longValue();
        }
        return String.valueOf(code).equals(String.valueOf(input));
    }

    private static Object convertArgument(Object input, Class<?> paramType) {
        try {
            if (paramType == String.class) {
                return String.valueOf(input);
            }
            if (paramType == int.class || paramType == Integer.class) {
                return input instanceof Number number ? number.intValue() : Integer.valueOf(String.valueOf(input));
            }
            if (paramType == long.class || paramType == Long.class) {
                return input instanceof Number number ? number.longValue() : Long.valueOf(String.valueOf(input));
            }
        } catch (NumberFormatException ex) {
            return null;
        }
        return null;
    }

    /** 读 {@code @JsonValue} 访问器输出；无标注或输出为 null 返回 null（jackson-annotations，DQ-005）。 */
    private static Object readJsonValue(Object value, Method method, Field field) {
        if (method != null) {
            return invokeQuietly(method, value);
        }
        if (field != null) {
            try {
                return field.get(value);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("@JsonValue 字段读取失败：" + value.getClass().getName(), e);
            }
        }
        return null;
    }

    private static Object invokeQuietly(Method method, Object target) {
        try {
            return method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("枚举 " + method.getName() + "() 调用失败：" + target.getClass().getName(), e);
        }
    }

    private static Method findJsonValueMethod(Class<?> type) {
        for (Method method : type.getMethods()) {
            JsonValue annotation = method.getAnnotation(JsonValue.class);
            if (annotation != null && annotation.value() && method.getParameterCount() == 0) {
                makeAccessible(method);
                return method;
            }
        }
        return null;
    }

    private static Field findJsonValueField(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            JsonValue annotation = field.getAnnotation(JsonValue.class);
            if (annotation != null && annotation.value()) {
                makeAccessible(field);
                return field;
            }
        }
        return null;
    }

    /** {@code code()} 方法约定（与 YesNo/Enabled 同款，无参返回 code）。 */
    private static Method findCodeMethod(Class<?> type) {
        try {
            Method method = type.getMethod("code");
            if (method.getParameterCount() == 0) {
                makeAccessible(method);
                return method;
            }
        } catch (NoSuchMethodException ignored) {
            // 无 code() 约定，走 name() 兜底（DQ-005）
        }
        return null;
    }

    /** {@code fromCode} 静态方法约定（与 YesNo/Enabled 同款）。 */
    private static Method findFromCode(Class<?> type) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals("fromCode")
                    && Modifier.isStatic(method.getModifiers())
                    && method.getParameterCount() == 1) {
                makeAccessible(method);
                return method;
            }
        }
        return null;
    }

    /** 枚举基类解析：带类体的常量运行时类为匿名子类，常量表须回落到枚举基类。 */
    private static Class<?> resolveEnumBase(Class<?> type) {
        if (type.isEnum()) {
            return type;
        }
        Class<?> parent = type.getSuperclass();
        return parent != null && parent.isEnum() ? parent : null;
    }

    private static void makeAccessible(java.lang.reflect.AccessibleObject member) {
        try {
            member.setAccessible(true);
        } catch (RuntimeException e) {
            // 模块封装下不可达时交由后续调用按原语义失败
        }
    }
}
