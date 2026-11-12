package com.acme.framework.flex.support;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;

import java.time.Instant;

/**
 * 审计基类（FR-008/009/010，12.2 能力交付物）：雪花主键（12.3.1 禁自增）、审计字段、乐观锁版本、软删除标记。
 *
 * <p>查询过滤（FR-009 默认过滤已删除记录）经 {@code @Column(isLogicDelete = true)} 落基类
 * （MyBatis-Flex 逻辑删除语义：查询自动追加 deleted=false、删除=置标记、重复删除幂等）；
 * 乐观锁经 {@code @Column(version = true)}（0 行更新=并发冲突，仓储层转 FlexError.STALE_WRITE）；
 * 雪花主键与审计填充由全局 Insert/Update 监听器完成（FlexAutoConfiguration 经 MyBatisFlexCustomizer
 * 注册在基类 AuditBase 上，按 isAssignableFrom 匹配覆盖所有子类实体）。</p>
 */
public abstract class AuditBase {

    /** 雪花主键（12.3.1 禁自增；KeyType.None=由 InsertListener 显式赋值，禁框架隐式策略）。 */
    @Id(keyType = KeyType.None)
    @Column("id")
    private Long id;

    @Column("created_at")
    private Instant createdAt;

    @Column("created_by")
    private String createdBy;

    @Column("updated_at")
    private Instant updatedAt;

    @Column("updated_by")
    private String updatedBy;

    /** 乐观锁版本（FR-010）：按实体更新时自动作为更新条件并递增。 */
    @Column(version = true)
    private Long version;

    /** 软删除标记（FR-009）：逻辑删除列；配套 BooleanLogicDeleteProcessor（deleted 是 Boolean 非 int）。 */
    @Column(isLogicDelete = true)
    private Boolean deleted;

    /** 插入前缺省填充（12.3.1、6.2：雪花主键 + 操作人永不缺失）；仅供本包 AuditFillListener 调用。 */
    void applyInsertDefaults(long id, String operator, Instant now) {
        if (this.id == null) {
            this.id = id;
        }
        if (deleted == null) {
            deleted = Boolean.FALSE;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (createdBy == null) {
            createdBy = operator;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (updatedBy == null) {
            updatedBy = operator;
        }
        if (version == null) {
            version = 0L;
        }
    }

    /** 更新前维护审计字段（FR-008）；仅供本包 AuditFillListener 调用。 */
    void applyUpdateDefaults(String operator, Instant now) {
        updatedAt = now;
        updatedBy = operator;
    }

    public Long getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public Long getVersion() {
        return version;
    }

    /**
     * 乐观锁版本回填（FR-010）：MyBatis-Flex 在 SQL 端自增版本（{@code version = version + 1}），
     * 不写回实体；仓储层 {@code update} 成功后调用本方法同步实体版本。
     */
    public void setVersion(Long version) {
        this.version = version;
    }

    public Boolean getDeleted() {
        return deleted;
    }
}
