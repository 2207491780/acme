package com.acme.platform.system.application;

import com.acme.common.core.BizException;
import com.acme.framework.web.support.WebError;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.dto.AdminSystemConfigUpdate;
import com.acme.platform.system.domain.SysConfig;
import com.acme.platform.system.infrastructure.repository.SysConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;

/**
 * 租户级系统配置用例（REQ-20261116-230354：每租户一行，upsert；租户隔离由 @TenantId 过滤面承担）。
 * GET 缺省视图在接口层组装；PUT 校验时长取值集 + 乐观锁（沿用既有 CRUD 口径）。
 */
@Service
public class SystemConfigUseCase {

    /** 闲置锁屏时长合法取值（契约确认记录⑦：0/1/5/15 分钟）。 */
    private static final Set<Integer> ALLOWED_IDLE_TIMEOUTS_MS = Set.of(0, 60_000, 300_000, 900_000);

    private final SysConfigRepository configRepository;

    public SystemConfigUseCase(SysConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    public Optional<SysConfig> findCurrent() {
        return configRepository.findCurrent();
    }

    @Transactional
    public SysConfig update(AdminSystemConfigUpdate request) {
        if (!ALLOWED_IDLE_TIMEOUTS_MS.contains(request.lockIdleTimeoutMs())) {
            throw new BizException(SystemError.SYSTEM_CONFIG_INVALID);
        }
        SysConfig current = configRepository.findCurrent().orElse(null);
        if (current == null) {
            // 无行时缺省视图 version="0"：携带非 0 版本即与缺省态冲突
            if (request.version() != 0L) {
                throw new BizException(WebError.CONCURRENCY_CONFLICT);
            }
            return configRepository.save(new SysConfig(request.watermarkEnabled(), request.lockIdleTimeoutMs()));
        }
        if (!request.version().equals(current.getVersion())) {
            throw new BizException(WebError.CONCURRENCY_CONFLICT);
        }
        current.setWatermarkEnabled(request.watermarkEnabled());
        current.setLockIdleTimeoutMs(request.lockIdleTimeoutMs());
        return configRepository.save(current);
    }
}