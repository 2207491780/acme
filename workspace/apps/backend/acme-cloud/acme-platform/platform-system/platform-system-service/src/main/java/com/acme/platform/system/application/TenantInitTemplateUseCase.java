package com.acme.platform.system.application;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.common.core.BizException;
import com.acme.framework.web.support.WebError;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.dto.AdminTenantInitTemplateContent;
import com.acme.platform.system.api.dto.AdminTenantInitTemplateCreate;
import com.acme.platform.system.api.dto.AdminTenantInitTemplateUpdate;
import com.acme.platform.system.api.dto.AdminTenantInitTemplateView;
import com.acme.platform.system.domain.SysTenantInitTemplate;
import com.acme.platform.system.infrastructure.repository.SysTenantInitTemplateRepository;
import com.acme.platform.system.infrastructure.repository.SysTenantPackageRepository;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 租户开通初始化模板用例（REQ-20261122-233426 确认②④）：模板 CRUD + content 落库校验。
 * 平台级目录表（sys_tenant_init_template 无租户列、不过滤、无旁路需求）。
 *
 * <p>content 校验（确认③ PARAM_INVALID 口径 400，拒绝面=契约明列两项+结构完整性扩展）：空角色集
 * 拒绝 / 保留前缀权限码拒绝（保留域单源复用 {@link TenantInitUseCase#RESERVED_PERMISSION_PREFIXES}）/
 * 重复 roleKey、空白 roleKey/roleName/权限码拒绝（结构完整性，开通必败项）。configEntries：configKey
 * 非空白且不重复即可入库（契约未钉死键词汇——快照原样保存，与 tester AMB-4 行存在性锚点同口径）；
 * 已知键（{@link #KNOWN_CONFIG_KEYS}=sys_config 六键词汇）的值须可解析（lockIdleTimeoutMs 复用
 * {@link SystemConfigUseCase#ALLOWED_IDLE_TIMEOUTS_MS} 取值集），未知键开通时 WARN 跳过（固定列
 * schema 无处可写，不阻断开通）。校验失败统一 TENANT_TEMPLATE_CONTENT_INVALID——content 内部
 * record 不挂 bean 约束，结构错误单一业务码承载（避免 VALIDATION_FAILED/CONTENT_INVALID 双口径分裂）。</p>
 *
 * <p>快照语义（确认④）：模板保存仅影响后续开通；已开通租户不回写（init 时按当时 content 落库，
 * 与套餐下发模型刻意区分）。模板删除无占用守卫（不被引用绑定，删除不影响存量租户）。</p>
 */
@Service
public class TenantInitTemplateUseCase {

    /**
     * configEntries.configKey 合法词汇（sys_config 可写列的 camelCase 键，与 AdminSystemConfigUpdate
     * 字段同词汇——固定列 schema 决定未知键无处可写，保存即拒）。
     */
    static final Set<String> KNOWN_CONFIG_KEYS = Set.of("watermarkEnabled", "captchaEnabled",
            "lockIdleTimeoutMs", "footerCopyright", "footerVisible", "watermarkTemplate");

    /** 模板管理员用户名前缀上限（合成余量：前缀 ≤32 + 租户编码 ≤64 需 ≤ sys_user.username 64，V1 口径）。 */
    static final int USERNAME_PREFIX_MAX_LENGTH = 32;

    private final SysTenantInitTemplateRepository templateRepository;
    private final SysTenantPackageRepository packageRepository;
    private final JsonMapper jsonMapper;

    public TenantInitTemplateUseCase(SysTenantInitTemplateRepository templateRepository,
                                     SysTenantPackageRepository packageRepository,
                                     JsonMapper jsonMapper) {
        this.templateRepository = templateRepository;
        this.packageRepository = packageRepository;
        this.jsonMapper = jsonMapper;
    }

    /** 模板分页（keyword 对 code/name 模糊；排序 id asc，套餐分页同款口径）。 */
    public PageResult<AdminTenantInitTemplateView> page(PageQuery query, String keyword) {
        QueryWrapper wrapper = QueryWrapper.create();
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            wrapper.where(SysTenantInitTemplate::getCode).like(like)
                    .or(SysTenantInitTemplate::getName).like(like);
        }
        wrapper.orderBy(SysTenantInitTemplate::getId, true);
        PageResult<SysTenantInitTemplate> result = templateRepository.findAll(wrapper, query.page(), query.size());
        return new PageResult<>(result.records().stream().map(this::view).toList(), result.total());
    }

    /** 模板创建（重码拒绝；content 落库校验；默认套餐存在性校验；content 序列化 jsonb 快照落库）。 */
    @Transactional
    public AdminTenantInitTemplateView create(AdminTenantInitTemplateCreate request) {
        if (templateRepository.existsByCode(request.code())) {
            throw new BizException(SystemError.TENANT_TEMPLATE_DUPLICATE);
        }
        requirePackageExists(request.defaultPackageId());
        SysTenantInitTemplate template = templateRepository.save(new SysTenantInitTemplate(
                request.code(), request.name(), request.remark(),
                serialize(request.content()), request.defaultPackageId()));
        return view(template);
    }

    /**
     * 模板更新（同创建校验；content 全量替换——快照语义不影响已开通租户）。version 可空：
     * 携带时前置校验（CONCURRENCY_CONFLICT）；不携带时跳过前置校验——仓储 save 以装载行
     * version 作 WHERE 条件（FR-010 DB 级乐观锁兜底，0 行=STALE_WRITE）。
     */
    @Transactional
    public AdminTenantInitTemplateView update(Long templateId, AdminTenantInitTemplateUpdate request) {
        SysTenantInitTemplate template = get(templateId);
        if (request.version() != null && !request.version().equals(template.getVersion())) {
            throw new BizException(WebError.CONCURRENCY_CONFLICT);
        }
        if (templateRepository.existsByCodeAndIdNot(request.code(), templateId)) {
            throw new BizException(SystemError.TENANT_TEMPLATE_DUPLICATE);
        }
        requirePackageExists(request.defaultPackageId());
        template.setCode(request.code());
        template.setName(request.name());
        template.setRemark(request.remark());
        template.setContent(serialize(request.content()));
        template.setDefaultPackageId(request.defaultPackageId());
        template = templateRepository.save(template);
        return view(template);
    }

    /** 模板删除（软删除；无引用绑定不设占用守卫——快照语义下存量租户不受影响）。 */
    @Transactional
    public void delete(Long templateId) {
        templateRepository.delete(get(templateId));
    }

    /** 模板详情（@AuditLog beforeQuery 数据源；不存在 → TENANT_TEMPLATE_NOT_FOUND）。 */
    public SysTenantInitTemplate get(Long templateId) {
        return templateRepository.findById(templateId)
                .orElseThrow(() -> new BizException(SystemError.TENANT_TEMPLATE_NOT_FOUND));
    }

    // ---- content 落库校验（确认②：保留前缀拒绝 / 空角色集拒绝，PARAM_INVALID 口径）----

    /**
     * content 业务校验 + 规范化（供开通面复用同一口径）：返回校验通过的规范化内容。
     * 校验链见类注；normalize=空白可选项归 null（前缀/昵称/配置值）。
     */
    AdminTenantInitTemplateContent validateAndNormalize(AdminTenantInitTemplateContent content) {
        if (content == null || content.rolePresets() == null || content.rolePresets().isEmpty()) {
            throw new BizException(SystemError.TENANT_TEMPLATE_CONTENT_INVALID);
        }
        Set<String> roleKeys = new HashSet<>();
        for (AdminTenantInitTemplateContent.RolePreset preset : content.rolePresets()) {
            if (preset == null || isBlank(preset.roleKey()) || isBlank(preset.roleName())
                    || !roleKeys.add(preset.roleKey().trim())) {
                throw new BizException(SystemError.TENANT_TEMPLATE_CONTENT_INVALID);
            }
            // 空白码剔除、重复码去重后必须仍有成员（角色禁零授权空挂）
            Set<String> codes = new LinkedHashSet<>();
            for (String code : preset.permissionCodes()) {
                if (code == null || code.isBlank()) {
                    throw new BizException(SystemError.TENANT_TEMPLATE_CONTENT_INVALID);
                }
                codes.add(code.trim());
            }
            if (codes.isEmpty()) {
                throw new BizException(SystemError.TENANT_TEMPLATE_CONTENT_INVALID);
            }
            // 保留前缀拒绝（单源复用 TenantInitUseCase 保留域，确认②「仅允许非保留前缀」）
            for (String code : codes) {
                if (TenantInitUseCase.RESERVED_PERMISSION_PREFIXES.stream().anyMatch(code::startsWith)) {
                    throw new BizException(SystemError.TENANT_TEMPLATE_CONTENT_INVALID);
                }
            }
        }
        Set<String> seenConfigKeys = new HashSet<>();
        for (AdminTenantInitTemplateContent.ConfigEntry entry : content.configEntries()) {
            if (entry == null || isBlank(entry.configKey())
                    || !seenConfigKeys.add(entry.configKey().trim())) {
                throw new BizException(SystemError.TENANT_TEMPLATE_CONTENT_INVALID);
            }
            // 已知键值可解析性校验（未知键快照原样保存、开通时 WARN 跳过——键词汇契约未钉死）
            if (KNOWN_CONFIG_KEYS.contains(entry.configKey().trim())) {
                validateConfigValue(entry.configKey().trim(), entry.value());
            }
        }
        if (!isBlank(content.adminUsernamePrefix())
                && content.adminUsernamePrefix().trim().length() > USERNAME_PREFIX_MAX_LENGTH) {
            // 前缀超上限（合成余量不足——开通请求租户编码最长 64，前缀+编码合成用户名将越界
            // sys_user.username varchar(64)）：保存即拒
            throw new BizException(SystemError.TENANT_TEMPLATE_CONTENT_INVALID);
        }
        return content;
    }

    /** 布尔/整型配置值可解析性校验（lockIdleTimeoutMs 复用系统配置取值集；字符串键放行任意值）。 */
    private void validateConfigValue(String configKey, String value) {
        switch (configKey) {
            case "watermarkEnabled", "captchaEnabled", "footerVisible" -> {
                if (!isBooleanLiteral(value)) {
                    throw new BizException(SystemError.TENANT_TEMPLATE_CONTENT_INVALID);
                }
            }
            case "lockIdleTimeoutMs" -> {
                Integer parsed;
                try {
                    parsed = value == null ? null : Integer.valueOf(value.trim());
                } catch (NumberFormatException ex) {
                    throw new BizException(SystemError.TENANT_TEMPLATE_CONTENT_INVALID);
                }
                if (parsed == null || !SystemConfigUseCase.ALLOWED_IDLE_TIMEOUTS_MS.contains(parsed)) {
                    throw new BizException(SystemError.TENANT_TEMPLATE_CONTENT_INVALID);
                }
            }
            default -> {
                // footerCopyright / watermarkTemplate：字符串键，任意值（null=清除）放行
            }
        }
    }

    private static boolean isBooleanLiteral(String value) {
        return value != null && (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false"));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String serialize(AdminTenantInitTemplateContent content) {
        validateAndNormalize(content);
        return jsonMapper.writeValueAsString(content);
    }

    private AdminTenantInitTemplateView view(SysTenantInitTemplate template) {
        return new AdminTenantInitTemplateView(template.getId(), template.getCode(), template.getName(),
                template.getRemark(), deserialize(template.getContent()), template.getDefaultPackageId(),
                template.getCreatedAt(), template.getUpdatedAt(), template.getVersion());
    }

    /** jsonb 快照 → 结构化内容（库内字符串为保存时序列化产物，往返无损）。 */
    AdminTenantInitTemplateContent deserialize(String content) {
        try {
            return jsonMapper.readValue(content, AdminTenantInitTemplateContent.class);
        } catch (RuntimeException ex) {
            throw new BizException(SystemError.TENANT_TEMPLATE_CONTENT_INVALID);
        }
    }

    private void requirePackageExists(Long packageId) {
        if (packageId != null && !packageRepository.existsById(packageId)) {
            throw new BizException(SystemError.TENANT_PACKAGE_NOT_FOUND);
        }
    }
}
