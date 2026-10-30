package com.acme.framework.jpa.support;

import com.acme.common.contract.PageResult;

import java.util.List;

/**
 * 查询封装面（FR-011；使用方唯一入口，6.5）：条件 + 分页 + 排序查询，零 QueryDSL API 暴露（BR-021、AC-009）。
 * 分页协议=common-contract.PageResult（6.5）；无结果按分页协议返回空集（9.4.1）。
 */
public interface EntityQueries {

    /** 列表查询（忽略分页参数）。 */
    <T> List<T> list(Class<T> entity, QuerySpec<T> spec);

    /** 分页查询（取 spec 分页参数）。 */
    <T> PageResult<T> page(Class<T> entity, QuerySpec<T> spec);

    /** 计数查询。 */
    <T> long count(Class<T> entity, QuerySpec<T> spec);
}
