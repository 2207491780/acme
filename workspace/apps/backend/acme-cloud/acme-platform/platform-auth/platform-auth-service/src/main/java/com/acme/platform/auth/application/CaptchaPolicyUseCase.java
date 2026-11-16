package com.acme.platform.auth.application;

import com.acme.platform.auth.infrastructure.client.SystemConfigGateway;
import com.acme.platform.system.api.CaptchaPolicyView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 验证码策略用例（REQ-20261117-011514）：登录页按租户显隐验证码的公开查询薄编排
 * （Feign 聚合经 SystemConfigGateway，服务身份主体 Q-D08）；
 * 下游不可用 fail-closed 视为开启（AC-6 不因降级放宽安全），实时读不引入缓存（确认记录④）。
 */
@Service
public class CaptchaPolicyUseCase {

    private static final Logger log = LoggerFactory.getLogger(CaptchaPolicyUseCase.class);

    private final SystemConfigGateway systemConfigGateway;

    public CaptchaPolicyUseCase(SystemConfigGateway systemConfigGateway) {
        this.systemConfigGateway = systemConfigGateway;
    }

    /** 租户验证码策略当前生效值（无配置行缺省关闭）；查询失败 fail-closed 返回开启（WARN 记录）。 */
    public CaptchaPolicyView findCaptchaPolicy(String tenantCode) {
        try {
            return systemConfigGateway.findCaptchaPolicy(tenantCode);
        } catch (RuntimeException ex) {
            log.warn("验证码策略查询失败，fail-closed 视为开启：tenantCode={}", tenantCode, ex);
            return new CaptchaPolicyView(true);
        }
    }
}
