package com.acme.business.content.api;

/**
 * 内容状态（BR-001 四值互斥；openapi ContentStatus）。
 * 常量名即 wire 形态（EnumCodeModule name() 兜底口径，同 system-api EnableStatus 先例）：
 * JSON / 查询参数 / 库列值（@Enumerated(STRING)）三处同为小写，勿改常量拼写。
 *
 * <p>状态机谓词（BR-013 合法迁移集合）随枚举单点持有（架构设计 3.2：{@code canTransitTo}）；
 * 领域行为入口为 {@code BizContent.transitTo}（M-08 业务逻辑单点），枚举仅纯谓词无副作用。</p>
 */
public enum ContentStatus {

    /** 草稿。 */
    draft,

    /** 审核中。 */
    reviewing,

    /** 已发布。 */
    published,

    /** 已驳回。 */
    rejected;

    /**
     * BR-013 合法迁移判定（集合外迁移为非法）：draft→reviewing、reviewing→published、
     * reviewing→rejected、rejected→draft、published→draft（下线）；同态迁移由调用方按幂等处理（10.3.1）。
     */
    public boolean canTransitTo(ContentStatus target) {
        return switch (this) {
            case draft -> target == reviewing;
            case reviewing -> target == published || target == rejected;
            case rejected -> target == draft;
            case published -> target == draft;
        };
    }
}
