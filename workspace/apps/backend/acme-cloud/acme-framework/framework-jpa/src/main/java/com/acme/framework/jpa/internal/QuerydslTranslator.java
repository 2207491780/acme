package com.acme.framework.jpa.internal;

import com.acme.framework.jpa.support.QuerySpec;
import com.acme.framework.jpa.support.SortDir;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.ComparablePath;
import com.querydsl.core.types.dsl.PathBuilder;
import com.querydsl.core.types.dsl.StringPath;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;

import java.util.Collection;
import java.util.List;

/**
 * QueryDSL 翻译收敛点（6.5 隔离规则：QueryDSL API 触碰面仅此一处，BR-021、WQ-1）：
 * {@link QuerySpec} 引擎无关模型 → QueryDSL 表达式；未来替换查询引擎只改本类与装配。
 */
public class QuerydslTranslator {

    private final JPAQueryFactory queryFactory;

    public QuerydslTranslator(JPAQueryFactory queryFactory) {
        if (queryFactory == null) {
            throw new IllegalArgumentException("queryFactory 不能为空");
        }
        this.queryFactory = queryFactory;
    }

    /** 列表查询（忽略分页参数）。 */
    public <T> List<T> list(QuerySpec<T> spec) {
        PathBuilder<T> root = root(spec);
        return build(root, spec).fetch();
    }

    /** 分页查询；无结果按分页协议返回空集（9.4.1）。 */
    public <T> List<T> page(QuerySpec<T> spec) {
        PathBuilder<T> root = root(spec);
        JPAQuery<T> query = build(root, spec);
        if (spec.page() != null && spec.size() != null) {
            query.offset((long) (spec.page() - 1) * spec.size()).limit(spec.size());
        }
        return query.fetch();
    }

    /** 计数查询。 */
    public <T> long count(QuerySpec<T> spec) {
        PathBuilder<T> root = root(spec);
        Long total = queryFactory.select(root.count()).from(root).where(predicate(root, spec)).fetchOne();
        return total == null ? 0L : total;
    }

    private <T> PathBuilder<T> root(QuerySpec<T> spec) {
        return new PathBuilder<>(spec.entity(), "_e");
    }

    private <T> JPAQuery<T> build(PathBuilder<T> root, QuerySpec<T> spec) {
        JPAQuery<T> query = queryFactory.select(root).from(root).where(predicate(root, spec));
        for (QuerySpec.Order order : spec.orders()) {
            query = order.dir() == SortDir.DESC
                    ? query.orderBy(root.getComparable(order.field(), Comparable.class).desc())
                    : query.orderBy(root.getComparable(order.field(), Comparable.class).asc());
        }
        return query;
    }

    private <T> BooleanExpression predicate(PathBuilder<T> root, QuerySpec<T> spec) {
        BooleanExpression expression = null;
        for (QuerySpec.Condition condition : spec.conditions()) {
            BooleanExpression current = toPredicate(root, condition);
            expression = expression == null ? current : expression.and(current);
        }
        return expression;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> BooleanExpression toPredicate(PathBuilder<T> root, QuerySpec.Condition condition) {
        String field = condition.field();
        Object value = condition.value();
        return switch (condition.op()) {
            case EQ -> root.get(field).eq(value);
            case NE -> root.get(field).ne(value);
            case GE -> ((ComparablePath) root.getComparable(field, Comparable.class)).goe((Comparable) value);
            case GT -> ((ComparablePath) root.getComparable(field, Comparable.class)).gt((Comparable) value);
            case LE -> ((ComparablePath) root.getComparable(field, Comparable.class)).loe((Comparable) value);
            case LT -> ((ComparablePath) root.getComparable(field, Comparable.class)).lt((Comparable) value);
            case STARTS_WITH -> string(root, field).startsWith(String.valueOf(value));
            case ENDS_WITH -> string(root, field).endsWith(String.valueOf(value));
            case CONTAINS -> string(root, field).contains(String.valueOf(value));
            case IN -> root.get(field).in((Collection<?>) value);
            case IS_NULL -> root.get(field).isNull();
            case IS_NOT_NULL -> root.get(field).isNotNull();
        };
    }

    private <T> StringPath string(PathBuilder<T> root, String field) {
        return root.getString(field);
    }
}
