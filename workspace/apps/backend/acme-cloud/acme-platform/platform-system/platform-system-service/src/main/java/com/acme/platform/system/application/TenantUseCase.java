package com.acme.platform.system.application;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.common.core.BizException;
import com.acme.framework.tenant.support.IgnoreTenant;
import com.acme.framework.web.support.WebError;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.dto.AdminTenantCreate;
import com.acme.platform.system.api.dto.AdminTenantUpdate;
import com.acme.platform.system.domain.SysTenant;
import com.acme.platform.system.infrastructure.repository.SysTenantRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 租户管理用例（FR-006 Q-004，架构设计 5.1 租户行）：平台管理跨租户场景显式声明并纳入审计（12.4.2）；
 * 无删除能力——停用代替（FR-007）；默认租户不可停用（种子保护）。
 */
@Service
public class TenantUseCase {

    /** 默认租户编码（种子固定；停用保护判定）。 */
    public static final String DEFAULT_TENANT_CODE = "default";

    private final SysTenantRepository tenantRepository;

    public TenantUseCase(SysTenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Transactional
    public SysTenant create(AdminTenantCreate request) {
        if (tenantRepository.existsByTenantCode(request.tenantCode())) {
            throw new BizException(SystemError.TENANT_CODE_DUPLICATED);
        }
        return tenantRepository.save(new SysTenant(request.tenantCode(), request.tenantName(), request.remark()));
    }

    /** 租户详情（跨租户平台管理场景，显式旁路声明 + 审计 12.4.2）。 */
    @IgnoreTenant("tenant-detail-lookup")
    public SysTenant get(Long tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new BizException(SystemError.TENANT_NOT_FOUND));
    }

    @Transactional
    public SysTenant update(Long tenantId, AdminTenantUpdate request) {
        SysTenant tenant = get(tenantId);
        if (request.version() == null || !request.version().equals(tenant.getVersion())) {
            throw new BizException(WebError.CONCURRENCY_CONFLICT);
        }
        tenant.setTenantName(request.tenantName());
        tenant.setRemark(request.remark());
        return tenantRepository.save(tenant);
    }

    /** 启用 / 停用（默认租户不可停用）。 */
    @Transactional
    public void updateStatus(Long tenantId, EnableStatus status) {
        SysTenant tenant = get(tenantId);
        if (status == EnableStatus.DISABLED && DEFAULT_TENANT_CODE.equals(tenant.getTenantCode())) {
            throw new BizException(SystemError.TENANT_DISABLE_DENIED);
        }
        tenant.setStatus(status);
        tenantRepository.save(tenant);
    }

    /** 分页查询（跨租户平台管理场景，显式旁路声明 + 审计 12.4.2）。 */
    @IgnoreTenant("tenant-list-lookup")
    public PageResult<SysTenant> page(PageQuery query, String keyword) {
        Specification<SysTenant> spec = (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim() + "%";
                predicates.add(cb.or(cb.like(root.get("tenantCode"), like),
                        cb.like(root.get("tenantName"), like)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Page<SysTenant> page = tenantRepository.findAll(spec,
                PageRequest.of(query.page() - 1, query.size(), Sort.by(Sort.Direction.ASC, "tenantCode")));
        return new PageResult<>(page.getContent(), page.getTotalElements());
    }
}
