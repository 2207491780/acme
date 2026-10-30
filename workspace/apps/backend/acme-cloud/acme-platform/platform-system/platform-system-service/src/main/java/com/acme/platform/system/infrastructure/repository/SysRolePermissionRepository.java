package com.acme.platform.system.infrastructure.repository;

import com.acme.platform.system.domain.SysRolePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/** 角色-权限点绑定仓库（绑定表物理删除，7.2.7）。 */
public interface SysRolePermissionRepository extends JpaRepository<SysRolePermission, Long> {

    List<SysRolePermission> findByRoleId(Long roleId);

    boolean existsByPermissionId(Long permissionId);

    List<SysRolePermission> findByRoleIdIn(Collection<Long> roleIds);

    @Modifying
    @Query("delete from SysRolePermission b where b.roleId = :roleId")
    int deleteByRoleId(@Param("roleId") Long roleId);
}
