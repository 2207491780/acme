package com.acme.business.content.domain;

import com.acme.business.content.ContentError;
import com.acme.business.content.api.ContentStatus;
import com.acme.common.core.BizException;
import com.acme.framework.flex.support.AuditBase;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 内容主实体（架构设计 3.2；biz_content）：标题 / 分类松引用 / 作者冗余快照（Q-11=A）/
 * 状态机 / 浏览量存量值（Q-14=B）/ 摘要 / 正文 / 首图预留；软删 BR-015（双端不可见）。
 *
 * <p>领域行为（M-08 单点）：{@link #rename}（BR-003）、{@link #transitTo}（BR-013）、
 * {@link #forceDraft}（批量下线管理覆盖，QD-06）；category_code 对分类表为松引用（3.1 无外键）。</p>
 */
@Table("biz_content")
public class BizContent extends AuditBase {

    @Column("title")
    private String title;

    @Column("category_code")
    private String categoryCode;

    @Column("author")
    private String author;

    @Column("status")
    private ContentStatus status;

    @Column("views")
    private Long views;

    @Column("excerpt")
    private String excerpt;

    /** 正文（Q-10=A 必做）；迁移列型 text（架构设计 3.2，列型以迁移脚本为准 12.3.3）。 */
    @Column("content")
    private String content;

    @Column("cover")
    private String cover;

    protected BizContent() {
    }

    public BizContent(String title, String categoryCode, String author, ContentStatus status,
                      Long views, String excerpt, String content, String cover) {
        this.title = title;
        this.categoryCode = categoryCode;
        this.author = author;
        this.status = status;
        this.views = views == null ? 0L : views;
        this.excerpt = excerpt;
        this.content = content;
        this.cover = cover;
    }

    /**
     * 标题行内修改（FR-008）：trim 后非空方可保存（BR-003）；空标题拒绝并提示（business-content-01-0002，不静默忽略）。
     */
    public void rename(String newTitle) {
        if (newTitle == null || newTitle.trim().isEmpty()) {
            throw new BizException(ContentError.TITLE_EMPTY);
        }
        this.title = newTitle.trim();
    }

    /**
     * 状态流转（FR-009）：合法迁移集合 BR-013；同态迁移由调用方按幂等处理（10.3.1，不判非法）；
     * 集合外迁移抛领域错误 business-content-01-0003。
     */
    public void transitTo(ContentStatus target) {
        if (target == null || !this.status.canTransitTo(target)) {
            throw new BizException(ContentError.ILLEGAL_TRANSITION);
        }
        this.status = target;
    }

    /** 批量下线管理覆盖动作（BR-004 / QD-06：无条件置 draft；BR-013 状态机仅约束单条流转 FR-009）。 */
    public void forceDraft() {
        this.status = ContentStatus.draft;
    }

    public String getTitle() {
        return title;
    }

    public String getCategoryCode() {
        return categoryCode;
    }

    public String getAuthor() {
        return author;
    }

    public ContentStatus getStatus() {
        return status;
    }

    public Long getViews() {
        return views;
    }

    public String getExcerpt() {
        return excerpt;
    }

    public String getContent() {
        return content;
    }

    public String getCover() {
        return cover;
    }
}
