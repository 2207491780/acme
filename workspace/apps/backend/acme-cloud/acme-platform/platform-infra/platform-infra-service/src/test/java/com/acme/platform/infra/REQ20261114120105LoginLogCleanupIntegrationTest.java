package com.acme.platform.infra;

import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.common.core.BizException;
import com.acme.framework.job.support.JobError;
import com.acme.framework.job.support.TaskRegistry;
import com.acme.framework.job.support.TaskTrigger;
import com.acme.platform.infra.domain.JobExecution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * REQ-20261114-120105 AC-7（infra 侧）：login-log-retention-cleanup 任务注册 + 手动触发受理
 * （platform-infra；Testcontainers PG+Redis 装配；无 Docker 自动跳过）。
 *
 * <p>口径说明（TL 裁决确认记录④⑦：C 方案——infra 注册任务、Feign 调 auth 内部端点、表访问收敛 auth）：</p>
 * <ul>
 *   <li>真实清理断言落 platform-auth 侧 {@code REQ20261114120105LoginLogRetentionPurgeIntegrationTest}
 *       （真实 V6 表 + auth 内部端点走库）；本文件不建表、不触表（不在 infra schema 自建表——裁决 4）。</li>
 *   <li>触发链路已钉死（TL 回填，确认记录⑧）：异步执行（TaskTrigger，不同步传播）；Feign 失败时
 *       执行记录仍回填 FAILED+finished_at——本文件 finished_at 轮询断言满足，无需桩件；
 *       清理行为正确性由 auth 侧文件兜底覆盖。</li>
 *   <li>注册表断言经 TaskRegistry.find（@JobHandler 收集面）；执行表列名（finished_at）
 *       按既有 job 域先例（AMB-1）。</li>
 * </ul>
 */
class REQ20261114120105LoginLogCleanupIntegrationTest extends AbstractInfraIntegrationTest {

    private static final String JOB_KEY = "login-log-retention-cleanup";
    private static final Duration TRIGGER_SETTLE = Duration.ofSeconds(10);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TaskRegistry taskRegistry;

    @Autowired
    private TaskTrigger taskTrigger;

    @MockitoBean
    private com.acme.platform.infra.infrastructure.client.AuthorizationGateway authorizationGateway;

    /** ShedLock 行清理 + 内存注册表同步（同 JobScheduleIntegrationTest 口径：只删行不清缓存
     *  会让下一次获取跳过 INSERT 直走 UPDATE，0 行 → 误判锁被持）。 */
    @Autowired
    private net.javacrumbs.shedlock.core.LockProvider lockProvider;

    private void clearShedLockState() {
        jdbcTemplate.update("delete from shedlock_locks");
        if (lockProvider instanceof net.javacrumbs.shedlock.support.StorageBasedLockProvider storage) {
            storage.clearCache();
        }
    }

    /** 跨域清理 Feign 桩（AMB-2）：platform-auth 在本测试上下文不存在，任务体经该桩受理触发——
     *  清理行为断言在 auth 侧 REQ20261114120105LoginLogRetentionPurgeIntegrationTest，本类不重复。 */
    @MockitoBean
    private com.acme.platform.infra.internal.client.LoginLogCleanupClient cleanupClient;

    @BeforeEach
    void setUp() {
        when(cleanupClient.purgeCleanup()).thenReturn(
                new com.acme.platform.system.api.ContractResult<>(true, "0", null, 0));
    }

    @AfterEach
    void cleanUp() {
        // 共享环境脏数据 teardown：执行记录 + ShedLock 锁行 + 内存注册表同步（本文件不触 sys_login_log）
        jdbcTemplate.update("delete from sys_job_execution");
        clearShedLockState();
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-7 前置：任务注册表含 login-log-retention-cleanup ----------

    @Test
    void should_registerLoginLogRetentionCleanup_when_queryTaskRegistry() {
        assertThat(taskRegistry.find(JOB_KEY))
                .as("任务注册表应含 " + JOB_KEY + "（@JobHandler 收集面）").isPresent();
    }

    // ---------- AC-7：手动触发受理（执行记录落库；清理行为断言在 auth 侧文件，见类注释） ----------

    @Test
    void should_acceptTriggerAndRecordExecution_when_loginLogRetentionCleanupRun() throws Exception {
        triggerJob(JOB_KEY);

        awaitTrue(TRIGGER_SETTLE, () -> {
            Long finished = jdbcTemplate.queryForObject(
                    "select count(*) from sys_job_execution where finished_at is not null", Long.class);
            return finished != null && finished > 0;
        });
    }

    // ---------- 支撑 ----------

    /** 直接触发任务（TaskTrigger 与调度同径互斥，REQ-20261121-200724 确认⑦；旧 HTTP 面已随接口清理下线）；
     *  TASK_RUNNING 短窗重试。 */
    private void triggerJob(String jobKey) throws InterruptedException {
        long deadline = System.nanoTime() + TRIGGER_SETTLE.toNanos();
        while (true) {
            try {
                taskTrigger.trigger(jobKey, JobExecution.TRIGGER_MANUAL);
                return;
            } catch (BizException ex) {
                if (JobError.TASK_RUNNING == ex.errorCode() && System.nanoTime() < deadline) {
                    Thread.sleep(200L);
                } else {
                    throw ex;
                }
            }
        }
    }

    private static void awaitTrue(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("轮询被中断", ex);
            }
        }
        org.assertj.core.api.Assertions.fail("等待条件超时（%s）", timeout);
    }
}
