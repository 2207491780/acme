package com.acme.platform.system.application;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.common.core.BizException;
import com.acme.framework.web.support.WebError;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.dto.AdminPositionCreate;
import com.acme.platform.system.api.dto.AdminPositionUpdate;
import com.acme.platform.system.domain.SysPosition;
import com.acme.platform.system.infrastructure.repository.SysPositionRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRepository;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 岗位管理用例（REQ-20261114-120104；租户归属，(tenant_id, position_code) 租户内唯一）：
 * 被用户引用的岗位删除拒绝 → POSITION_IN_USE（AC-2）。
 */
@Service
public class PositionUseCase {

    private final SysPositionRepository positionRepository;
    private final SysUserRepository userRepository;

    public PositionUseCase(SysPositionRepository positionRepository, SysUserRepository userRepository) {
        this.positionRepository = positionRepository;
        this.userRepository = userRepository;
    }

    /** 创建岗位（编码租户内唯一；status 缺省 ENABLED）。 */
    @Transactional
    public SysPosition create(AdminPositionCreate request) {
        if (positionRepository.existsByPositionCode(request.code())) {
            throw new BizException(SystemError.POSITION_CODE_DUPLICATED);
        }
        return positionRepository.save(new SysPosition(request.code(), request.name(), request.status()));
    }

    /** 岗位详情（不存在 → 404）。 */
    public SysPosition get(Long positionId) {
        return positionRepository.findById(positionId)
                .orElseThrow(() -> new BizException(SystemError.POSITION_NOT_FOUND));
    }

    /** 启用 / 停用（停用仅影响新引用面，既有引用语义不变）。 */
    @Transactional
    public void updateStatus(Long positionId, EnableStatus status) {
        SysPosition position = get(positionId);
        position.setStatus(status);
        positionRepository.save(position);
    }

    /** 修改岗位（编码租户内唯一排除自身；乐观锁版本对照）。 */
    @Transactional
    public SysPosition update(Long positionId, AdminPositionUpdate request) {
        SysPosition position = get(positionId);
        if (request.version() == null || !request.version().equals(position.getVersion())) {
            throw new BizException(WebError.CONCURRENCY_CONFLICT);
        }
        if (positionRepository.existsByPositionCodeAndIdNot(request.code(), positionId)) {
            throw new BizException(SystemError.POSITION_CODE_DUPLICATED);
        }
        position.setPositionCode(request.code());
        position.setPositionName(request.name());
        if (request.status() != null) {
            position.setStatus(request.status());
        }
        return positionRepository.save(position);
    }

    /** 删除岗位（不存在 → 404；被用户引用拒绝 → POSITION_IN_USE；软删）。 */
    @Transactional
    public void delete(Long positionId) {
        SysPosition position = get(positionId);
        if (userRepository.existsByPositionId(positionId)) {
            throw new BizException(SystemError.POSITION_IN_USE);
        }
        positionRepository.delete(position);
    }

    /** 分页查询（编码 / 名称模糊；按 id 倒序稳定排序）。 */
    public PageResult<SysPosition> page(PageQuery query, String keyword, EnableStatus status) {
        QueryWrapper wrapper = QueryWrapper.create();
        boolean hasCondition = false;
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            wrapper.where(SysPosition::getPositionCode).like(like)
                    .or(SysPosition::getPositionName).like(like);
            hasCondition = true;
        }
        if (status != null) {
            if (hasCondition) {
                wrapper.and(SysPosition::getStatus).eq(status);
            } else {
                wrapper.where(SysPosition::getStatus).eq(status);
            }
        }
        wrapper.orderBy(SysPosition::getId, false);
        return positionRepository.findAll(wrapper, query.page(), query.size());
    }

    /** 启用岗位全集（后续部门树 / 表单下拉等消费面预留）。 */
    public List<SysPosition> listEnabled() {
        return positionRepository.findByStatus(EnableStatus.ENABLED);
    }
}
