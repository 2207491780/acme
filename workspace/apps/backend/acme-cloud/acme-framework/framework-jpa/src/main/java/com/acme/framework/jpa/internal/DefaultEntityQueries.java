package com.acme.framework.jpa.internal;

import com.acme.common.contract.PageResult;
import com.acme.framework.jpa.support.EntityQueries;
import com.acme.framework.jpa.support.QuerySpec;

import java.util.List;

/**
 * {@link EntityQueries} 缺省实现（6.5；bean 不对使用方暴露 QueryDSL 类型，BR-021）。
 */
public class DefaultEntityQueries implements EntityQueries {

    private final QuerydslTranslator translator;

    public DefaultEntityQueries(QuerydslTranslator translator) {
        this.translator = translator;
    }

    @Override
    public <T> List<T> list(Class<T> entity, QuerySpec<T> spec) {
        return translator.list(requireSpec(entity, spec));
    }

    @Override
    public <T> PageResult<T> page(Class<T> entity, QuerySpec<T> spec) {
        QuerySpec<T> checked = requireSpec(entity, spec);
        return new PageResult<>(translator.page(checked), translator.count(checked));
    }

    @Override
    public <T> long count(Class<T> entity, QuerySpec<T> spec) {
        return translator.count(requireSpec(entity, spec));
    }

    private <T> QuerySpec<T> requireSpec(Class<T> entity, QuerySpec<T> spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec 不能为空");
        }
        if (entity != spec.entity()) {
            throw new IllegalArgumentException("entity 与 spec 不一致");
        }
        return spec;
    }
}
