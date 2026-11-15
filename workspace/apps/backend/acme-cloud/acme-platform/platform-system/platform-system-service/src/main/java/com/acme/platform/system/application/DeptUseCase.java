package com.acme.platform.system.application;

import com.acme.common.core.BizException;
import com.acme.framework.web.support.WebError;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.dto.AdminDeptCreate;
import com.acme.platform.system.api.dto.AdminDeptUpdate;
import com.acme.platform.system.domain.SysDept;
import com.acme.platform.system.infrastructure.repository.SysDeptRepository;
import com.acme.platform.system.infrastructure.repository.SysRoleDeptRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 部门管理用例（REQ-20261114-120104；租户归属，树形经 parentId + path 物化路径维护）：
 * 删除守卫（确认记录③）：存在子部门 → DEPT_HAS_CHILDREN、存在用户归属 → DEPT_HAS_USERS；
 * 重挂父沿新父链防环，子树 path 前缀整体重写。
 */
@Service
public class DeptUseCase {

    private final SysDeptRepository deptRepository;
    private final SysUserRepository userRepository;
    private final SysRoleDeptRepository roleDeptRepository;

    public DeptUseCase(SysDeptRepository deptRepository, SysUserRepository userRepository,
                       SysRoleDeptRepository roleDeptRepository) {
        this.deptRepository = deptRepository;
        this.userRepository = userRepository;
        this.roleDeptRepository = roleDeptRepository;
    }

    /** 租户内全量部门（含禁用态，树组装在接口层 EntityViews.deptTree；按 sort_order / id 稳定序）。 */
    public List<SysDept> listAll() {
        return deptRepository.findAll(com.mybatisflex.core.query.QueryWrapper.create()
                .orderBy(SysDept::getSortOrder, true)
                .orderBy(SysDept::getId, true));
    }

    /** 部门详情（不存在 → 404）。 */
    public SysDept get(Long deptId) {
        return deptRepository.findById(deptId).orElseThrow(() -> new BizException(SystemError.DEPT_NOT_FOUND));
    }

    /** 创建部门（父节点须存在；insert 后按雪花 id 回填物化 path，乐观锁版本保持 0）。 */
    @Transactional
    public SysDept create(AdminDeptCreate request) {
        Long parentId = request.parentId() == null ? SysDept.ROOT_PARENT_ID : request.parentId();
        String parentPath = "/";
        if (parentId != SysDept.ROOT_PARENT_ID) {
            parentPath = get(parentId).getPath();
        }
        SysDept dept = deptRepository.save(new SysDept(parentId, request.name(), request.sortOrder(),
                request.status()));
        deptRepository.updatePath(dept.getId(), parentPath + dept.getId() + "/");
        dept.setPath(parentPath + dept.getId() + "/");
        return dept;
    }

    /** 修改部门（名称/排序/状态/父级；parentId null=不调整、0=移为根；sort/status null=不调整；重挂时防环并重写子树 path）。 */
    @Transactional
    public SysDept update(Long deptId, AdminDeptUpdate request) {
        SysDept dept = get(deptId);
        if (request.version() == null || !request.version().equals(dept.getVersion())) {
            throw new BizException(WebError.CONCURRENCY_CONFLICT);
        }
        applyParent(dept, request.parentId());
        dept.setDeptName(request.name());
        if (request.sortOrder() != null) {
            dept.setSortOrder(request.sortOrder());
        }
        if (request.status() != null) {
            dept.setStatus(request.status());
        }
        return deptRepository.save(dept);
    }

    /**
     * 重挂父（null=不调整；0=移为根；&gt;0=改父）：目标父须存在；目标父为自身或其子孙拒绝（DEPT_PARENT_CYCLE，
     * visited 集沿链防脏数据死循环，与角色 applyParent 同款）；重挂后本部门与子树 path 前缀整体重写。
     */
    private void applyParent(SysDept dept, Long targetParentId) {
        if (targetParentId == null || targetParentId.equals(dept.getParentId())) {
            return;
        }
        String newParentPath = "/";
        if (targetParentId != SysDept.ROOT_PARENT_ID) {
            SysDept targetParent = get(targetParentId);
            // 防环：目标父链向上游走命中自身即成环
            Long cursor = targetParentId;
            Set<Long> visited = new LinkedHashSet<>();
            while (cursor != null && cursor != SysDept.ROOT_PARENT_ID && visited.add(cursor)) {
                if (cursor.equals(dept.getId())) {
                    throw new BizException(SystemError.DEPT_PARENT_CYCLE);
                }
                cursor = get(cursor).getParentId();
            }
            newParentPath = targetParent.getPath();
        }
        rewriteSubtreePaths(dept, newParentPath);
        dept.setParentId(targetParentId);
    }

    /**
     * 子树 path 前缀重写（重挂装配步骤；部门树规模小，逐节点回填可接受——
     * 超大规模可改单条 SQL 字符串替换，因方言差异未引入）。
     */
    private void rewriteSubtreePaths(SysDept dept, String newParentPath) {
        String oldPath = dept.getPath() == null ? "/" + dept.getId() + "/" : dept.getPath();
        String newPath = newParentPath + dept.getId() + "/";
        for (SysDept node : deptRepository.findSubtree(oldPath)) {
            if (node.getId().equals(dept.getId())) {
                continue;
            }
            String suffix = node.getPath().substring(oldPath.length());
            deptRepository.updatePath(node.getId(), newPath + suffix);
        }
        dept.setPath(newPath);
        deptRepository.updatePath(dept.getId(), newPath);
    }

    /** 删除部门（不存在 → 404；存在子部门 / 存在用户归属拒绝，确认记录③；软删；CUSTOM 绑定行留存量、评估按空集收敛）。 */
    @Transactional
    public void delete(Long deptId) {
        SysDept dept = get(deptId);
        if (deptRepository.existsByParentId(deptId)) {
            throw new BizException(SystemError.DEPT_HAS_CHILDREN);
        }
        if (userRepository.existsByDeptId(deptId)) {
            throw new BizException(SystemError.DEPT_HAS_USERS);
        }
        deptRepository.delete(dept);
    }

    /** 子树部门 id 集（含自身；用户列表 deptId 入参含子部门递归用）。 */
    public List<Long> subtreeIds(Long deptId) {
        SysDept dept = get(deptId);
        List<Long> ids = new ArrayList<>();
        for (SysDept node : deptRepository.findSubtree(dept.getPath())) {
            ids.add(node.getId());
        }
        return ids;
    }

    /** CUSTOM 绑定引用判定（交付口径：删除部门不清理 role_dept 绑定，供运维观测；无删除守卫义务）。 */
    public boolean referencedByCustomScope(Long deptId) {
        return roleDeptRepository.existsByDeptId(deptId);
    }
}
