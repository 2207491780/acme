package com.acme.platform.system.infrastructure.repository;

import com.acme.platform.system.domain.SysUserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** 用户-角色绑定仓库（绑定表物理删除，7.2.7）。 */
public interface SysUserRoleRepository extends JpaRepository<SysUserRole, Long> {

    List<SysUserRole> findByUserId(Long userId);

    boolean existsByRoleId(Long roleId);

    boolean existsByUserIdAndRoleId(Long userId, Long roleId);

    @Modifying
    @Query("delete from SysUserRole b where b.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);

    List<SysUserRole> findByUserIdIn(List<Long> userIds);
}
