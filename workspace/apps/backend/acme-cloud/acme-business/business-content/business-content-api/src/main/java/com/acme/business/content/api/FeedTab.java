package com.acme.business.content.api;

/**
 * 首页横 Tab 枚举（openapi FeedTab；FR-001）。
 * 常量名即 wire 形态（EnumCodeModule name() 兜底口径）。
 *
 * <p>tab 未知值回空集不报错（FR-001 异常分支、对齐 mock 现行为），故入口按字符串宽容解析
 * （{@link #fromWire}），不做枚举强绑定。</p>
 */
public enum FeedTab {

    /** 推荐（内容流）。 */
    recommend,

    /** 关注（本期空集，QD-02）。 */
    follow,

    /** 同城（本期空集，QD-02）。 */
    city,

    /** 收藏（本期空集，QD-02）。 */
    library;

    /**
     * 宽容解析（FR-001）：未知 / 空值返回 null（调用方按空集处理，不报错）。
     */
    public static FeedTab fromWire(String raw) {
        if (raw == null) {
            return null;
        }
        for (FeedTab tab : values()) {
            if (tab.name().equals(raw.trim())) {
                return tab;
            }
        }
        return null;
    }
}
