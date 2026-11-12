package com.acme.common.contract;

/**
 * 分页查询协议基元（FR-023；Modules.md 2.1 推荐 record）：服务内与跨服务统一分页请求类型。
 * 缺省归一为首页与默认页大小；越界显式失败，不静默纠正。
 *
 * @implNote 暂缓 DP-008：分页参数与业务查询对象的组合方式随首个使用方补全。
 */
public record PageQuery(Integer page, Integer size) {

    /** 默认页大小（本次重构定稿）。 */
    public static final int DEFAULT_SIZE = 20;

    /** 页大小上界（本次重构定稿：协议入口防大页拖垮查询）。 */
    public static final int MAX_SIZE = 200;

    public PageQuery {
        if (page == null) {
            page = 1;
        }
        if (size == null) {
            size = DEFAULT_SIZE;
        }
        if (page < 1) {
            throw new IllegalArgumentException("page 必须 ≥ 1");
        }
        if (size < 1) {
            throw new IllegalArgumentException("size 必须 ≥ 1");
        }
        if (size > MAX_SIZE) {
            throw new IllegalArgumentException("size 超出上界 " + MAX_SIZE);
        }
    }

    /** 便捷工厂：缺省分页（首页、默认页大小）。 */
    public static PageQuery firstPage() {
        return new PageQuery(null, null);
    }
}
