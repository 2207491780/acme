package com.acme.framework.jpa.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 引擎无关查询模型（FR-011、Q-D08）：条件 + 排序 + 分页；与 {@link EntityQueries} 配套使用，
 * 使用方零 QueryDSL API 暴露（BR-021、AC-009）。
 */
public final class QuerySpec<T> {

    private final Class<T> entity;
    private final List<Condition> conditions = new ArrayList<>();
    private final List<Order> orders = new ArrayList<>();
    private Integer page;
    private Integer size;

    private QuerySpec(Class<T> entity) {
        if (entity == null) {
            throw new IllegalArgumentException("entity 不能为空");
        }
        this.entity = entity;
    }

    /** 构造查询模型（6.5）。 */
    public static <T> QuerySpec<T> of(Class<T> entity) {
        return new QuerySpec<>(entity);
    }

    /** 追加条件（6.5）。 */
    public QuerySpec<T> where(FieldRef<T> field, Op op, Object value) {
        if (field == null || op == null) {
            throw new IllegalArgumentException("field/op 不能为空");
        }
        if (op == Op.IN && !(value instanceof java.util.Collection<?>)) {
            // IN 值构造期校验（6.5）：非集合值显式失败，避免翻译期 ClassCastException
            throw new IllegalArgumentException("Op.IN 的 value 必须是集合");
        }
        conditions.add(new Condition(field.name(), op, value));
        return this;
    }

    /** 追加排序（6.5）。 */
    public QuerySpec<T> orderBy(FieldRef<T> field, SortDir dir) {
        if (field == null || dir == null) {
            throw new IllegalArgumentException("field/dir 不能为空");
        }
        orders.add(new Order(field.name(), dir));
        return this;
    }

    /** 设置分页（6.5；page 从 1 起）。 */
    public QuerySpec<T> page(int page, int size) {
        if (page < 1 || size < 1) {
            throw new IllegalArgumentException("page/size 必须 ≥ 1");
        }
        this.page = page;
        this.size = size;
        return this;
    }

    public Class<T> entity() {
        return entity;
    }

    public List<Condition> conditions() {
        return Collections.unmodifiableList(conditions);
    }

    public List<Order> orders() {
        return Collections.unmodifiableList(orders);
    }

    public Integer page() {
        return page;
    }

    public Integer size() {
        return size;
    }

    /** 条件模型（引擎无关）。 */
    public record Condition(String field, Op op, Object value) {
    }

    /** 排序模型（引擎无关）。 */
    public record Order(String field, SortDir dir) {
    }
}
