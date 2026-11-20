package com.acme.platform.system.application;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.common.core.BizException;
import com.acme.framework.web.support.WebError;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.dto.AdminPermissionCreate;
import com.acme.platform.system.api.dto.AdminPermissionUpdate;
import com.acme.platform.system.domain.SysPermission;
import com.acme.platform.system.infrastructure.repository.SysPermissionRepository;
import com.acme.platform.system.infrastructure.repository.SysRolePermissionRepository;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 权限点管理用例（FR-008，架构设计 5.1 权限点行；平台级目录，编码全局唯一）。
 */
@Service
public class PermissionUseCase {

    private final SysPermissionRepository permissionRepository;
    private final SysRolePermissionRepository rolePermissionRepository;

    public PermissionUseCase(SysPermissionRepository permissionRepository,
                             SysRolePermissionRepository rolePermissionRepository) {
        this.permissionRepository = permissionRepository;
        this.rolePermissionRepository = rolePermissionRepository;
    }

    @Transactional
    public SysPermission create(AdminPermissionCreate request) {
        if (permissionRepository.existsByPermissionCodeAndIdNot(request.permissionCode(), -1L)) {
            throw new BizException(SystemError.PERMISSION_CODE_DUPLICATED);
        }
        return permissionRepository.save(new SysPermission(request.permissionCode(), request.permissionName(),
                request.permissionType(), request.httpMethod(), request.httpPath()));
    }

    public SysPermission get(Long permissionId) {
        return permissionRepository.findById(permissionId)
                .orElseThrow(() -> new BizException(SystemError.PERMISSION_NOT_FOUND));
    }

    @Transactional
    public SysPermission update(Long permissionId, AdminPermissionUpdate request) {
        SysPermission permission = get(permissionId);
        if (request.version() == null || !request.version().equals(permission.getVersion())) {
            throw new BizException(WebError.CONCURRENCY_CONFLICT);
        }
        permission.setPermissionName(request.permissionName());
        permission.setHttpMethod(request.httpMethod());
        permission.setHttpPath(request.httpPath());
        return permissionRepository.save(permission);
    }

    /** 软删除（存在角色绑定拒绝；幂等）。 */
    @Transactional
    public void delete(Long permissionId) {
        permissionRepository.findById(permissionId).ifPresent(permission -> {
            if (rolePermissionRepository.existsByPermissionId(permissionId)) {
                throw new BizException(SystemError.PERMISSION_DELETE_DENIED);
            }
            permissionRepository.delete(permission);
        });
    }

    /** 分页查询（编码 / 名称模糊；status / httpMethod 精确过滤，REQ-20261120-214653；平台目录全租户可见）。 */
    public PageResult<SysPermission> page(PageQuery query, String keyword, EnableStatus status, String httpMethod) {
        QueryWrapper wrapper = QueryWrapper.create();
        boolean hasCondition = false;
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            // 关键字 OR 组括号分组（与 status/httpMethod 等外部条件 AND 组合语义正确；参照 UserUseCase.page 惯用式）
            java.util.function.Consumer<QueryWrapper> keywordGroup = sub -> sub
                    .where(SysPermission::getPermissionCode).like(like)
                    .or(SysPermission::getPermissionName).like(like);
            if (hasCondition) {
                wrapper.and(keywordGroup);
            } else {
                wrapper.where(keywordGroup);
            }
            hasCondition = true;
        }
        if (status != null) {
            if (hasCondition) {
                wrapper.and(SysPermission::getStatus).eq(status);
            } else {
                wrapper.where(SysPermission::getStatus).eq(status);
            }
            hasCondition = true;
        }
        if (httpMethod != null) {
            // http_method 为 NULL 的记录不命中任何动词筛选（SQL 相等比较对 NULL 恒为假）
            if (hasCondition) {
                wrapper.and(SysPermission::getHttpMethod).eq(httpMethod);
            } else {
                wrapper.where(SysPermission::getHttpMethod).eq(httpMethod);
            }
        }
        wrapper.orderBy(SysPermission::getPermissionCode, true);
        return permissionRepository.findAll(wrapper, query.page(), query.size());
    }

    public List<SysPermission> listByIds(List<Long> ids) {
        return permissionRepository.findAllById(ids);
    }
}
