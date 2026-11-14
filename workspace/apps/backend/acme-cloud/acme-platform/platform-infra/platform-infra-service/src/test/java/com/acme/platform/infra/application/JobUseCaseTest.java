package com.acme.platform.infra.application;

import com.acme.common.core.BizException;
import com.acme.framework.job.support.JobError;
import com.acme.framework.job.support.MutexMode;
import com.acme.framework.job.support.TaskDefinition;
import com.acme.framework.job.support.TaskRegistry;
import com.acme.framework.job.support.TaskTrigger;
import com.acme.framework.storage.config.StorageProperties;
import com.acme.platform.infra.InfraError;
import com.acme.platform.infra.domain.JobExecution;
import com.acme.platform.infra.infrastructure.repository.JobExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 任务调度用例单元测试（REQ-20261114-071607；纯 JUnit + Mockito，无 Spring）：
 * 注册表视图（AC-12 用例面）、手动触发返回执行记录 id（AC-13 用例面）、
 * 未知 jobKey JOB_NOT_FOUND、TASK_RUNNING → JOB_ALREADY_RUNNING 翻译（AC-14 用例面，
 * 端到端互斥由 PG 集成测试覆盖）。
 */
class JobUseCaseTest {

    private static final String JOB_KEY = "file-retention-cleanup";

    private TaskRegistry registry;
    private JobExecutionRepository jobExecutionRepository;
    private JobUseCase useCase;

    /** 触发目标桩件（镜像 FileRetentionCleanupJob#execute 签名；直接注册无代理，不走互斥切面）。 */
    public static class StubJob {

        public Long execute(String triggerType) {
            return 4242L;
        }
    }

    /** 执行中桩件（任务体抛 TASK_RUNNING，模拟未获锁拒绝语义）。 */
    public static class RunningJob {

        public Long execute(String triggerType) {
            throw new BizException(JobError.TASK_RUNNING, "任务正在执行: " + JOB_KEY);
        }
    }

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        registry = new TaskRegistry();
        jobExecutionRepository = mock(JobExecutionRepository.class);
        useCase = new JobUseCase(registry, new TaskTrigger(registry),
                jobExecutionRepository, new StorageProperties());
    }

    private void registerTask(Object target) throws NoSuchMethodException {
        Method execute = target.getClass().getMethod("execute", String.class);
        registry.register(new TaskDefinition(JOB_KEY, MutexMode.GLOBAL, null, null,
                target, execute, StubJob.class.getSimpleName() + "#execute"));
    }

    @Test
    void listContainsCleanupJobDescriptor() throws Exception {
        // AC-12 用例面：key/description/cron/enabled
        registerTask(new StubJob());

        List<JobView> views = useCase.list();

        assertThat(views).hasSize(1);
        JobView view = views.get(0);
        assertThat(view.key()).isEqualTo(JOB_KEY);
        assertThat(view.description()).isNotBlank();
        assertThat(view.cron()).isNotBlank();
        assertThat(view.enabled()).isTrue();
        assertThat(view.lastExecution()).isNull();
    }

    @Test
    void listCarriesLastExecutionSummary() throws Exception {
        registerTask(new StubJob());
        JobExecution execution = new JobExecution(JOB_KEY, JobExecution.STATUS_SUCCESS,
                Instant.parse("2026-11-14T03:00:00Z"), JobExecution.TRIGGER_SCHEDULED);
        when(jobExecutionRepository.findLatest(JOB_KEY)).thenReturn(Optional.of(execution));

        JobView view = useCase.list().get(0);

        assertThat(view.lastExecution()).isNotNull();
        assertThat(view.lastExecution().status()).isEqualTo(JobExecution.STATUS_SUCCESS);
        assertThat(view.lastExecution().startedAt()).isNotNull();
    }

    @Test
    void runReturnsExecutionIdWhenIdle() throws Exception {
        // AC-13 用例面：手动触发返回执行记录 id（触发来源 MANUAL）
        registerTask(new StubJob());

        Long executionId = useCase.run(JOB_KEY);

        assertThat(executionId).isEqualTo(4242L);
    }

    @Test
    void runUnknownJobKeyFailsWithJobNotFound() {
        assertThatThrownBy(() -> useCase.run("no-such-job"))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(InfraError.JOB_NOT_FOUND));
    }

    @Test
    void runWhileRunningFailsWithJobAlreadyRunning() throws Exception {
        // AC-14 用例面：TASK_RUNNING（未获锁）→ 契约码 JOB_ALREADY_RUNNING（不排队）
        registerTask(new RunningJob());

        assertThatThrownBy(() -> useCase.run(JOB_KEY))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(InfraError.JOB_ALREADY_RUNNING));
    }

    @Test
    void runWithBlankJobKeyFailsWithJobNotFound() {
        assertThatThrownBy(() -> useCase.run(" "))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(InfraError.JOB_NOT_FOUND));
    }
}
