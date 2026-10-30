package com.acme.platform.system.infrastructure.repository;

import com.acme.platform.system.domain.SysRoleMenu;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/** 角色-菜单绑定仓库（绑定表物理删除，7.2.7）。 */
public interface SysRoleMenuRepository extends JpaRepository<SysRoleMenu, Long> {

    List<SysRoleMenu> findByRoleId(Long roleId);

    boolean existsByMenuId(Long menuId);

    List<SysRoleMenu> findByRoleIdIn(Collection<Long> roleIds);

    @Modifying
    @Query("delete from SysRoleMenu b where b.roleId = :roleId")
    int deleteByRoleId(@Param("roleId") Long roleId);
}
