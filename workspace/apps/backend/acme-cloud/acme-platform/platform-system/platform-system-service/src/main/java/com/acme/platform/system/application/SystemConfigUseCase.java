package com.acme.platform.system.application;

import com.acme.common.core.BizException;
import com.acme.framework.tenant.support.IgnoreTenant;
import com.acme.framework.web.support.WebError;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.CaptchaPolicyView;
import com.acme.platform.system.api.TenantPublicInternalView;
import com.acme.platform.system.api.dto.AdminSystemConfigUpdate;
import com.acme.platform.system.domain.SysConfig;
import com.acme.platform.system.infrastructure.repository.SysConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 租户级系统配置用例（REQ-20261116-230354：每租户一行，upsert；租户隔离由 @TenantId 过滤面承担）。
 * GET 缺省视图在接口层组装；PUT 校验时长取值集 + 乐观锁（沿用既有 CRUD 口径）。
 * REQ-20261117-011514：captchaEnabled 开关随 upsert 全量生效；内部策略查询按租户代码直查
 * （sys_config.tenant_id 存租户代码，@IgnoreTenant 显式旁路声明 + 审计，12.4.2）。
 * REQ-20261117-025200 确认记录⑤：页脚版权两字段随 upsert 全量生效（未配置不显示，缺省 null/false）；
 * 内部 public-view 查询按租户代码直查（仅暴露页脚两字段，不含任何敏感配置）。
 * REQ-20261117-180000：水印模板随 upsert 全量生效（可空=未配置→仅用户名）；
 * 完整 {token} 占位符须属合法集，未知占位符拒绝（SYSTEM_WATERMARK_TEMPLATE_INVALID）。
 */
@Service
public class SystemConfigUseCase {

    /** 闲置锁屏时长合法取值（契约确认记录⑦：0/1/5/15 分钟）。 */
    private static final Set<Integer> ALLOWED_IDLE_TIMEOUTS_MS = Set.of(0, 60_000, 300_000, 900_000);

    /** 水印模板合法占位符（REQ-20261117-180000；与 core 渲染器 WATERMARK_PLACEHOLDERS 对齐）。 */
    private static final Set<String> WATERMARK_PLACEHOLDERS =
            Set.of("username", "nickname", "userId", "tenantCode", "deptName", "date", "time");

    /** 完整占位符 token（不匹配残缺花括号——残缺序列按字面量渲染，不做拒绝）。 */
    private static final Pattern WATERMARK_TOKEN = Pattern.compile("\\{([^{}]*)\\}");

    private final SysConfigRepository configRepository;

    public SystemConfigUseCase(SysConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    public Optional<SysConfig> findCurrent() {
        return configRepository.findCurrent();
    }

    /**
     * 按租户代码查验证码策略（REQ-20261117-011514 captcha-policy 内部端点数据源）；
     * 无配置行返回缺省关闭（迁移内置默认关闭，确认记录①）。
     */
    @IgnoreTenant("captcha-policy-lookup")
    public CaptchaPolicyView findCaptchaPolicyByTenant(String tenantCode) {
        return configRepository.findByTenantCode(tenantCode)
                .map(config -> new CaptchaPolicyView(Boolean.TRUE.equals(config.getCaptchaEnabled())))
                .orElse(new CaptchaPolicyView(false));
    }

    /**
     * 按租户代码查公开站点配置（REQ-20261117-025200 页脚版权，public-view 内部端点数据源）：
     * 无配置行返回缺省视图 footerCopyright=null / footerVisible=false（未配置不显示）；
     * 仅暴露页脚两字段，不含水印 / 验证码 / 锁屏等管理面配置。
     */
    @IgnoreTenant("public-view-lookup")
    public TenantPublicInternalView findPublicViewByTenant(String tenantCode) {
        return configRepository.findByTenantCode(tenantCode)
                .map(config -> new TenantPublicInternalView(config.getFooterCopyright(),
                        Boolean.TRUE.equals(config.getFooterVisible())))
                .orElse(new TenantPublicInternalView(null, false));
    }

    @Transactional
    public SysConfig update(AdminSystemConfigUpdate request) {
        if (!ALLOWED_IDLE_TIMEOUTS_MS.contains(request.lockIdleTimeoutMs())) {
            throw new BizException(SystemError.SYSTEM_CONFIG_INVALID);
        }
        validateWatermarkTemplate(request.watermarkTemplate());
        SysConfig current = configRepository.findCurrent().orElse(null);
        if (current == null) {
            // 无行时缺省视图 version="0"：携带非 0 版本即与缺省态冲突
            if (request.version() != 0L) {
                throw new BizException(WebError.CONCURRENCY_CONFLICT);
            }
            return configRepository.save(new SysConfig(request.watermarkEnabled(), request.captchaEnabled(),
                    request.lockIdleTimeoutMs(), request.footerCopyright(), request.footerVisible(),
                    request.watermarkTemplate()));
        }
        if (!request.version().equals(current.getVersion())) {
            throw new BizException(WebError.CONCURRENCY_CONFLICT);
        }
        current.setWatermarkEnabled(request.watermarkEnabled());
        current.setCaptchaEnabled(request.captchaEnabled());
        current.setLockIdleTimeoutMs(request.lockIdleTimeoutMs());
        current.setFooterCopyright(request.footerCopyright());
        current.setFooterVisible(request.footerVisible());
        current.setWatermarkTemplate(request.watermarkTemplate());
        return configRepository.save(current);
    }

    /** 水印模板占位符校验（REQ-20261117-180000）：null/无占位符字面量放行，未知完整 token 拒绝。 */
    private static void validateWatermarkTemplate(String template) {
        if (template == null) {
            return;
        }
        Matcher matcher = WATERMARK_TOKEN.matcher(template);
        while (matcher.find()) {
            if (!WATERMARK_PLACEHOLDERS.contains(matcher.group(1))) {
                throw new BizException(SystemError.SYSTEM_WATERMARK_TEMPLATE_INVALID);
            }
        }
    }
}
