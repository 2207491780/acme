package com.acme.platform.system;

import com.acme.common.context.TenantContext;
import com.acme.common.core.BizException;
import com.acme.framework.flex.support.FlexError;
import com.acme.platform.system.domain.SysRole;
import com.acme.platform.system.domain.SysUser;
import com.acme.platform.system.domain.SysUserRole;
import com.acme.platform.system.infrastructure.repository.SysRoleRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRepository;
import com.acme.platform.system.infrastructure.repository.SysUserRoleRepository;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.MyBatisSystemException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 仓储切片测试（16.1 等效隔离环境，H2 内存库 + MyBatis-Flex 真实 SQL 面）：
 * 保存审计/租户/版本、软删除幂等、乐观锁 0 行转 STALE_WRITE、绑定表物理删除重绑、
 * 分页 count 剥离排序、租户过滤 fail-closed（AC-016、10.3.1、FR-008/009/010）。
 */
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.flyway.enabled=false",
        "acme.flex.slow-sql.enabled=false"
})
class FlexRepositorySliceTest {

    @Autowired
    SysUserRepository userRepository;

    @Autowired
    SysRoleRepository roleRepository;

    @Autowired
    SysUserRoleRepository userRoleRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        // 共享上下文共享同一 H2 库：jdbc 直连物理清空（不经仓储过滤面）
        TenantContext.clear();
        jdbcTemplate.update("delete from sys_user_role");
        jdbcTemplate.update("delete from sys_user");
        jdbcTemplate.update("delete from sys_role");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // ---------- 保存：审计 + 租户 + 版本（FR-008/010、12.4.1） ----------

    @Test
    void saveInsertFillsAuditTenantVersionAndSnowflakeId() {
        TenantContext.bind(new TenantContext("t-1"));
        SysUser saved = userRepository.save(new SysUser("alice", "爱丽丝", null, null, false));
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getTenantId()).isEqualTo("t-1");
        assertThat(saved.getVersion()).isEqualTo(0L);
        assertThat(saved.getCreatedBy()).isEqualTo("system");
        assertThat(saved.getDeleted()).isFalse();
    }

    @Test
    void saveUpdateBumpsVersionInEntityAndDatabase() {
        TenantContext.bind(new TenantContext("t-1"));
        SysUser saved = userRepository.save(new SysUser("bob", "鲍勃", null, null, false));
        saved.setNickname("鲍勃二");
        SysUser updated = userRepository.save(saved);
        assertThat(updated.getVersion()).isEqualTo(1L);
        Long dbVersion = jdbcTemplate.queryForObject(
                "select version from sys_user where username='bob'", Long.class);
        assertThat(dbVersion).isEqualTo(1L);
    }

    @Test
    void saveWithStaleVersionThrowsStaleWrite() {
        TenantContext.bind(new TenantContext("t-1"));
        SysUser saved = userRepository.save(new SysUser("carol", "卡萝", null, null, false));
        saved.setNickname("卡萝二");
        userRepository.save(saved);
        // 库中 version=1；实体版本回拨为 0 → 0 行 → STALE_WRITE（AC-008 失败语义可判定）
        saved.setVersion(0L);
        saved.setNickname("并发冲突");
        assertThatThrownBy(() -> userRepository.save(saved))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                        .isEqualTo(FlexError.STALE_WRITE));
    }

    // ---------- 软删除（FR-009、10.3.1 幂等） ----------

    @Test
    void deleteHidesRowAndRepeatDeleteIsIdempotent() {
        TenantContext.bind(new TenantContext("t-1"));
        SysUser saved = userRepository.save(new SysUser("dave", "戴夫", null, null, false));
        userRepository.delete(saved);
        assertThat(userRepository.findById(saved.getId())).isEmpty();
        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from sys_user where username='dave'", Integer.class);
        assertThat(rows).isEqualTo(1);
        // 幂等：再删 0 行不抛
        userRepository.deleteById(saved.getId());
    }

    // ---------- 绑定表：物理删除 + 重绑（7.2.7） ----------

    @Test
    void bindingTableDeleteIsPhysicalAndRebindSucceeds() {
        TenantContext.bind(new TenantContext("t-1"));
        SysUser user = userRepository.save(new SysUser("erin", "艾琳", null, null, false));
        SysRole role = roleRepository.save(new SysRole("ops", "运维", null, false));
        userRoleRepository.saveAll(List.of(new SysUserRole(user.getId(), role.getId())));
        assertThat(userRoleRepository.findByUserId(user.getId())).hasSize(1);

        int removed = userRoleRepository.deleteByUserId(user.getId());
        assertThat(removed).isEqualTo(1);
        Integer rows = jdbcTemplate.queryForObject("select count(*) from sys_user_role", Integer.class);
        assertThat(rows).isZero();

        // 重绑无唯一键冲突（物理删除语义）
        userRoleRepository.saveAll(List.of(new SysUserRole(user.getId(), role.getId())));
        assertThat(userRoleRepository.existsByUserIdAndRoleId(user.getId(), role.getId())).isTrue();
    }

    // ---------- 分页：count 剥离排序、or 模糊（AC-004/005 等效） ----------

    @Test
    void pagedQueryWithOrLikeSortsAndCounts() {
        TenantContext.bind(new TenantContext("t-1"));
        userRepository.saveAll(List.of(
                new SysUser("alpha", "阿尔法", null, null, false),
                new SysUser("beta", "贝塔", null, null, false),
                new SysUser("gamma", "伽马", null, null, false)));
        QueryWrapper query = QueryWrapper.create()
                .where(SysUser::getUsername).like("alp").or(SysUser::getNickname).like("贝塔");
        var page = userRepository.findAll(query, 1, 10);
        assertThat(page.total()).isEqualTo(2L);
        assertThat(page.records()).extracting(SysUser::getUsername).containsExactlyInAnyOrder("alpha", "beta");

        // 无条件 + id 倒序分页
        var all = userRepository.findAll(QueryWrapper.create().orderBy(SysUser::getId, false), 1, 2);
        assertThat(all.total()).isEqualTo(3L);
        assertThat(all.records()).hasSize(2);
    }

    // ---------- 租户过滤（12.4.1、AC-016） ----------

    @Test
    void tenantIsolationAndFailClosed() {
        TenantContext.bind(new TenantContext("t-a"));
        userRepository.save(new SysUser("user-a", "租户A用户", null, null, false));
        TenantContext.bind(new TenantContext("t-b"));
        userRepository.save(new SysUser("user-b", "租户B用户", null, null, false));
        TenantContext.bind(new TenantContext("t-a"));
        assertThat(userRepository.findAll()).extracting(SysUser::getUsername).containsExactly("user-a");

        // 缺租户读空集 fail-closed
        TenantContext.bind(null);
        assertThat(userRepository.findAll()).isEmpty();
        // 缺租户写拒绝（TenantWriteGuard；异常经 MyBatis 包装为 MyBatisSystemException，root cause 保留
        // 错误码——web 层 GlobalExceptionTranslator.handleDataAccess 沿因果链还原业务语义）
        TenantContext.bind(null);
        assertThatThrownBy(() -> userRepository.save(new SysUser("ghost", "无租户", null, null, false)))
                .isInstanceOf(MyBatisSystemException.class)
                .hasRootCauseInstanceOf(BizException.class);
    }
}
