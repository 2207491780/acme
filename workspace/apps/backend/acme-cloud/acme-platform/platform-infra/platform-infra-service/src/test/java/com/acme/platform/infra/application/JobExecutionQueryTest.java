package com.acme.platform.infra.application;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.framework.job.support.MutexMode;
import com.acme.framework.job.support.TaskDefinition;
import com.acme.framework.job.support.TaskRegistry;
import com.acme.framework.job.support.TaskTrigger;
import com.acme.framework.storage.config.StorageProperties;
import com.acme.platform.infra.config.AuditRetentionProperties;
import com.acme.platform.infra.config.ExceptionLogRetentionProperties;
import com.acme.platform.infra.config.LoginLogRetentionProperties;
import com.acme.platform.infra.domain.JobExecution;
import com.acme.platform.infra.infrastructure.repository.JobExecutionRepository;
import com.acme.platform.infra.internal.job.AuditRetentionCleanupJob;
import com.acme.platform.infra.internal.job.OpExceptionRetentionCleanupJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 任务执行记录查询与新增清理任务登记单元测试（REQ-20261117-061017 确认②⑦；纯 JUnit + Mockito）：
 * executions 分页透传、视图 durationMs/id wire、DESCRIPTIONS 目录登记与 cron 来源。
 */
class JobExecutionQueryTest {

    private static final String EXCEPTION_JOB_KEY = OpExceptionRetentionCleanupJob.JOB_KEY;
    private static final String AUDIT_JOB_KEY = AuditRetentionCleanupJob.JOB_KEY;

    private TaskRegistry registry;
    private JobExecutionRepository jobExecutionRepository;
    private JobUseCase useCase;

    @BeforeEach
    void setUp() {
        registry = new TaskRegistry();
        jobExecutionRepository = mock(JobExecutionRepository.class);
        useCase = new JobUseCase(registry, new TaskTrigger(registry), jobExecutionRepository,
                new StorageProperties(), new LoginLogRetentionProperties(),
                new ExceptionLogRetentionProperties(), new AuditRetentionProperties());
    }

    private void registerTask(String jobKey, Object target) throws NoSuchMethodException {
        Method execute = target.getClass().getMethod("execute", String.class);
        registry.register(new TaskDefinition(jobKey, MutexMode.GLOBAL, null, null,
                target, execute, target.getClass().getSimpleName() + "#execute"));
    }

    /** 新增清理任务登记 DESCRIPTIONS 目录（确认② job key 字面量）。 */
    @Test
    void listContainsNewRetentionJobDescriptors() throws Exception {
        registerTask(EXCEPTION_JOB_KEY, new StubJob());
        registerTask(AUDIT_JOB_KEY, new StubJob());

        List<JobView> views = useCase.list();

        assertThat(views).extracting(JobView::key)
                .containsExactly(AUDIT_JOB_KEY, EXCEPTION_JOB_KEY);
        for (JobView view : views) {
            assertThat(view.description()).isNotBlank();
            assertThat(view.enabled()).isTrue();
        }
        JobView exceptionJob = views.stream()
                .filter(view -> EXCEPTION_JOB_KEY.equals(view.key())).findFirst().orElseThrow();
        assertThat(exceptionJob.cron())
                .isEqualTo(new ExceptionLogRetentionProperties().getRetentionCron());
        JobView auditJob = views.stream()
                .filter(view -> AUDIT_JOB_KEY.equals(view.key())).findFirst().orElseThrow();
        assertThat(auditJob.cron()).isEqualTo(new AuditRetentionProperties().getRetentionCron());
    }

    /** 执行记录分页视图：id wire 字符串、durationMs 计算、字段随行。 */
    @Test
    void should_returnExecutionViewsWithDuration_when_pageExecutions() throws Exception {
        JobExecution earlier = execution(11L, Instant.parse("2026-11-17T03:40:00Z"),
                Instant.parse("2026-11-17T03:40:01Z"), JobExecution.TRIGGER_MANUAL);
        JobExecution later = execution(12L, Instant.parse("2026-11-17T04:40:00Z"),
                null, JobExecution.TRIGGER_SCHEDULED);
        when(jobExecutionRepository.findAll(any(), org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(20)))
                .thenReturn(new PageResult<>(List.of(later, earlier), 2L));

        PageResult<JobExecutionView> page = useCase.listExecutions(EXCEPTION_JOB_KEY, new PageQuery(1, 20));

        assertThat(page.total()).isEqualTo(2L);
        assertThat(page.records()).hasSize(2);
        JobExecutionView first = page.records().getFirst();
        assertThat(first.id()).isEqualTo("12");
        assertThat(first.jobKey()).isEqualTo(EXCEPTION_JOB_KEY);
        assertThat(first.status()).isEqualTo(JobExecution.STATUS_RUNNING);
        assertThat(first.triggerType()).isEqualTo(JobExecution.TRIGGER_SCHEDULED);
        assertThat(first.durationMs()).as("缺终态时长为 null").isNull();
        JobExecutionView second = page.records().get(1);
        assertThat(second.id()).isEqualTo("11");
        assertThat(second.durationMs()).isEqualTo(1000L);
    }

    /** 未知 jobKey：空集语义（列表查询不显式失败）。 */
    @Test
    void should_returnEmptyPage_when_jobKeyHasNoExecutions() {
        when(jobExecutionRepository.findAll(any(), org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(20))).thenReturn(PageResult.empty());

        PageResult<JobExecutionView> page = useCase.listExecutions("no-such-job", new PageQuery(1, 20));

        assertThat(page.total()).isZero();
        assertThat(page.records()).isEmpty();
    }

    /** 实体构造（jobKey/status/startedAt/triggerType + 终态回填 + id 反射注入）。 */
    private static JobExecution execution(Long id, Instant startedAt, Instant finishedAt, String triggerType) {
        JobExecution execution = new JobExecution(EXCEPTION_JOB_KEY,
                finishedAt == null ? JobExecution.STATUS_RUNNING : JobExecution.STATUS_SUCCESS,
                startedAt, triggerType);
        execution.setFinishedAt(finishedAt);
        try {
            Field idField = com.acme.framework.flex.support.AuditBase.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(execution, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return execution;
    }

    /** 触发目标桩件（镜像清理任务 execute 签名）。 */
    public static class StubJob {
        public Long execute(String triggerType) {
            return 1L;
        }
    }
}
