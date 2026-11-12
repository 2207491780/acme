package com.acme.framework.storage.support;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 对象键（FR-011、架构设计 9.1）：通用路径约定「namespace/日期段/唯一名」；业务桶语义归业务侧。
 */
public final class ObjectKey {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final String namespace;
    private final String name;
    private final LocalDate date;

    private ObjectKey(String namespace, String name, LocalDate date) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace 不能为空");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        this.namespace = namespace.trim();
        this.name = name.trim();
        this.date = date == null ? LocalDate.now() : date;
    }

    /** 构造对象键（日期段取当天）。 */
    public static ObjectKey of(String namespace, String name) {
        return new ObjectKey(namespace, name, null);
    }

    /** 构造对象键（指定日期段）。 */
    public static ObjectKey of(String namespace, String name, LocalDate date) {
        return new ObjectKey(namespace, name, date);
    }

    public String namespace() {
        return namespace;
    }

    public String name() {
        return name;
    }

    public LocalDate date() {
        return date;
    }

    /** 存储路径 {@code namespace/yyyy/MM/dd/name}。 */
    public String path() {
        return namespace + "/" + DATE.format(date) + "/" + name;
    }

    @Override
    public String toString() {
        return path();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ObjectKey key && path().equals(key.path());
    }

    @Override
    public int hashCode() {
        return path().hashCode();
    }
}
