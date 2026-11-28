package com.acme.platform.system.domain;

import com.acme.framework.flex.support.AuditBase;
import com.acme.framework.flex.typehandler.JsonbTypeHandler;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 租户开通初始化模板（REQ-20261122-233426 确认②：平台级目录表，无 tenant_id、不参与租户过滤——
 * sys_tenant_package 同款 Q-D04 口径；软删除；uk(code) 由 DDL 承载，逻辑校验在用例层）。
 *
 * <p>content 为 jsonb 列、实体侧 String 承载（{@link JsonbTypeHandler} 负责方言映射，V23 先例），
 * 载荷形状 {@code {adminUsernamePrefix?, adminNickname?, rolePresets:[{roleKey,roleName,
 * permissionCodes[]}], configEntries:[{configKey,value}]}}（确认②；结构化承载归 api 层
 * AdminTenantInitTemplateContent，校验归 TenantInitTemplateUseCase——保留前缀/空角色集落库即拒）。</p>
 *
 * <p>开通时快照语义（确认④）：init 按开通时刻的 content 建角色集/绑授权/写配置/默认套餐，
 * 模板后改不影响已开通租户（非引用绑定，与套餐下发模型刻意区分）。</p>
 */
@Table("sys_tenant_init_template")
public class SysTenantInitTemplate extends AuditBase {

    @Column("code")
    private String code;

    @Column("name")
    private String name;

    @Column("remark")
    private String remark;

    @Column(value = "content", typeHandler = JsonbTypeHandler.class)
    private String content;

    /** 默认套餐 ID（可空=null 开通不绑定套餐；逻辑引用 sys_tenant_package.id，无 FK 对齐库内惯例）。 */
    @Column("default_package_id")
    private Long defaultPackageId;

    protected SysTenantInitTemplate() {
    }

    public SysTenantInitTemplate(String code, String name, String remark, String content,
                                 Long defaultPackageId) {
        this.code = code;
        this.name = name;
        this.remark = remark;
        this.content = content;
        this.defaultPackageId = defaultPackageId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Long getDefaultPackageId() {
        return defaultPackageId;
    }

    public void setDefaultPackageId(Long defaultPackageId) {
        this.defaultPackageId = defaultPackageId;
    }
}
