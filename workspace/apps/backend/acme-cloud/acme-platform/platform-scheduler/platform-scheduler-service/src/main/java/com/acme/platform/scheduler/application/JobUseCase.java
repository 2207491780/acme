package com.acme.platform.scheduler.application;

import com.acme.common.contract.InvalidRequestException;
import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.common.core.BizException;
import com.acme.common.core.Clock;
import com.acme.platform.scheduler.SchedulerError;
import com.acme.platform.scheduler.api.MisfirePolicy;
import com.acme.platform.scheduler.api.SchedulerJobStatus;
import com.acme.platform.scheduler.domain.SchJobDefinition;
import com.acme.platform.scheduler.infrastructure.repository.JobDefinitionRepository;
import com.acme.platform.scheduler.kernel.RetryPolicy;
import com.acme.platform.scheduler.kernel.SchedulerKernel;
import com.acme.platform.scheduler.kernel.TriggerCalculator;
import com.mybatisflex.core.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

/**
 * 任务定义管理用例（确认②④语义终稿；AC-2）：
 * 创建默认 DISABLED（显式启用走 /status 端点）；job_key 全局唯一（重复→SCHEDULER_JOB_KEY_DUPLICATE）；
 * job_key 创建后不可改（PUT 变更→SCHEDULER_JOB_KEY_IMMUTABLE）；更新带 @NotNull version（乐观锁）；
 * 触发参数 Spring CronExpression 校验（失败→SCHEDULER_TRIGGER_INVALID）。
 */
@Service("schedulerJobUseCase")
public class JobUseCase {

    private static final Logger log = LoggerFactory.getLogger(JobUseCase.class);

    /** 超时缺省毫秒（确认③：timeout_ms 可空=60000）。 */
    private static final long DEFAULT_TIMEOUT_MS = 60_000L;

    private final JobDefinitionRepository definitionRepository;
    private final SchedulerKernel kernel;
    private final TriggerCalculator triggerCalculator;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public JobUseCase(JobDefinitionRepository definitionRepository,
                      SchedulerKernel kernel,
                      TriggerCalculator triggerCalculator,
                      JsonMapper jsonMapper,
                      Clock schedulerClock) {
        this.definitionRepository = definitionRepository;
        this.kernel = kernel;
        this.triggerCalculator = triggerCalculator;
        this.jsonMapper = jsonMapper;
        this.clock = schedulerClock;
    }

    /** 创建（强制默认 DISABLED；job_key 唯一；必填字段显式校验；触发参数前置校验）。 */
    @Transactional
    public JobView create(JobSaveRequest request) {
        if (request.jobKey() == null || request.jobKey().isBlank()
                || request.appName() == null || request.appName().isBlank()
                || request.handler() == null || request.handler().isBlank()
                || request.triggerType() == null) {
            throw new InvalidRequestException("创建任务必填：jobKey / appName / handler / triggerType");
        }
        String jobKey = request.jobKey().trim();
        if (definitionRepository.findByJobKey(jobKey).isPresent()) {
            throw new BizException(SchedulerError.SCHEDULER_JOB_KEY_DUPLICATE);
        }
        SchJobDefinition definition = new SchJobDefinition();
        definition.setJobKey(jobKey);
        // 创建默认 DISABLED（确认②：显式启用走 /status 端点；入参不携带 status）
        definition.setStatus(SchedulerJobStatus.DISABLED);
        definition.setNextFireTime(null);
        mergeSaveRequest(definition, request);
        definitionRepository.save(definition);
        log.info("任务定义创建：jobKey={}, triggerType={}, status=DISABLED", definition.getJobKey(),
                definition.getTriggerType());
        return viewOf(definition);
    }

    /**
     * 更新（部分语义：null 字段=保持既有值；job_key 不可改——携带变更值即拒绝
     * SCHEDULER_JOB_KEY_IMMUTABLE；乐观锁版本承载并发）。
     * 更新后重排触发面：启用态按新参数推进 next_fire_time 并回填时间轮；DISABLED 态由启用动作重算。
     */
    @Transactional
    public JobView update(Long id, JobSaveRequest request) {
        SchJobDefinition definition = definitionRepository.findById(id)
                .orElseThrow(() -> new BizException(SchedulerError.SCHEDULER_JOB_NOT_FOUND));
        if (request.jobKey() != null && !request.jobKey().trim().equals(definition.getJobKey())) {
            throw new BizException(SchedulerError.SCHEDULER_JOB_KEY_IMMUTABLE,
                    "job_key 创建后不可修改: " + definition.getJobKey());
        }
        Instant previousFireTime = definition.getNextFireTime();
        mergeSaveRequest(definition, request);
        if (definition.getStatus() == SchedulerJobStatus.ENABLED) {
            Instant now = clock.now();
            definition.setNextFireTime(previousFireTime == null
                    ? triggerCalculator.initialFireTime(definition, now)
                    : triggerCalculator.firstFutureFireTime(definition, previousFireTime, now));
        }
        definitionRepository.save(definition);
        if (definition.getStatus() == SchedulerJobStatus.ENABLED) {
            kernel.rescheduleWheel(definition);
        }
        log.info("任务定义更新：id={}, jobKey={}", id, definition.getJobKey());
        return viewOf(definition);
    }

    /**
     * 启停（确认② /status 专用端点）：启用→计算初始排期并回填时间轮；停用→移除时间轮条目。
     */
    @Transactional
    public JobView updateStatus(Long id, SchedulerJobStatus status) {
        SchJobDefinition definition = definitionRepository.findById(id)
                .orElseThrow(() -> new BizException(SchedulerError.SCHEDULER_JOB_NOT_FOUND));
        definition.setStatus(status);
        if (status == SchedulerJobStatus.ENABLED) {
            Instant now = clock.now();
            if (definition.getNextFireTime() == null) {
                definition.setNextFireTime(triggerCalculator.initialFireTime(definition, now));
            }
            definitionRepository.save(definition);
            kernel.rescheduleWheel(definition);
            log.info("任务启用：jobKey={}, nextFireTime={}", definition.getJobKey(), definition.getNextFireTime());
        } else {
            definitionRepository.save(definition);
            kernel.removeWheel(definition.getId());
            log.info("任务停用：jobKey={}", definition.getJobKey());
        }
        return viewOf(definition);
    }

    /** 删除（软删；执行历史保留；时间轮条目移除）。 */
    @Transactional
    public void delete(Long id) {
        SchJobDefinition definition = definitionRepository.findById(id)
                .orElseThrow(() -> new BizException(SchedulerError.SCHEDULER_JOB_NOT_FOUND));
        kernel.removeWheel(definition.getId());
        definitionRepository.delete(definition);
        log.info("任务定义删除：jobKey={}", definition.getJobKey());
    }

    /**
     * 内部一次性任务注册（BPM 联动，确认④；注册即 ENABLED 并在 onceFireAt 触发下发，AC-12）。
     * 撞既有 job_key → SCHEDULER_JOB_KEY_DUPLICATE。
     */
    public SchJobDefinition registerOneShot(com.acme.platform.scheduler.api.OneShotJobRegisterRequest request) {
        return kernel.registerOneShot(request);
    }

    /** 旧值快照（@AuditLog beforeQuery SpEL 引用；REQ-20261117-061017 确认③；不存在返回 null）。 */
    public JobView getSnapshot(Long id) {
        return definitionRepository.findById(id).map(JobUseCase::viewOf).orElse(null);
    }

    /** 手动触发（MANUAL execution；返回执行记录 id）。 */
    public Long trigger(Long id) {
        SchJobDefinition definition = definitionRepository.findById(id)
                .orElseThrow(() -> new BizException(SchedulerError.SCHEDULER_JOB_NOT_FOUND));
        return kernel.triggerManual(definition);
    }

    /** 定义分页列表（过滤：jobKey 精确 / appName 精确 / status；job_key 升序稳定排序）。 */
    public PageResult<JobView> list(String jobKey, String appName, SchedulerJobStatus status, PageQuery query) {
        QueryWrapper wrapper = QueryWrapper.create();
        if (jobKey != null && !jobKey.isBlank()) {
            wrapper.and(SchJobDefinition::getJobKey).eq(jobKey.trim());
        }
        if (appName != null && !appName.isBlank()) {
            wrapper.and(SchJobDefinition::getAppName).eq(appName.trim());
        }
        if (status != null) {
            wrapper.and(SchJobDefinition::getStatus).eq(status);
        }
        wrapper.orderBy(SchJobDefinition::getJobKey, true);
        PageResult<SchJobDefinition> page = definitionRepository.findAll(wrapper, query.page(), query.size());
        List<JobView> records = page.records().stream().map(JobUseCase::viewOf).toList();
        return new PageResult<>(records, page.total());
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 合并保存请求（部分语义：携带字段=替换，null=保持既有值；创建时全量必填已在 create 显式校验）。
     * 合并后统一前置校验触发参数与重试策略（失败→SCHEDULER_TRIGGER_INVALID / SCHEDULER_RETRY_POLICY_INVALID）。
     */
    private void mergeSaveRequest(SchJobDefinition definition, JobSaveRequest request) {
        if (request.appName() != null) {
            definition.setAppName(request.appName().trim());
        }
        if (request.handler() != null) {
            definition.setHandler(request.handler().trim());
        }
        if (request.triggerType() != null) {
            definition.setTriggerType(request.triggerType());
        }
        if (request.cronExpr() != null) {
            definition.setCronExpr(request.cronExpr());
        }
        if (request.fixedRateMs() != null) {
            definition.setFixedRateMs(request.fixedRateMs());
        }
        if (request.onceFireAt() != null) {
            definition.setOnceFireAt(request.onceFireAt());
        }
        if (request.param() != null) {
            definition.setParam(request.param());
        }
        if (request.retryPolicy() != null) {
            definition.setRetryPolicy(request.retryPolicyJson(jsonMapper));
        }
        if (request.shardTotal() != null) {
            definition.setShardTotal(request.shardTotal() < 1 ? 1 : request.shardTotal());
        }
        if (request.shardable() != null) {
            definition.setShardable(request.shardable());
        }
        if (request.timeoutMs() != null) {
            definition.setTimeoutMs(request.timeoutMs() <= 0 ? DEFAULT_TIMEOUT_MS : request.timeoutMs());
        }
        if (request.misfirePolicy() != null) {
            definition.setMisfirePolicy(request.misfirePolicy());
        }
        if (request.remark() != null) {
            definition.setRemark(request.remark());
        }
        // 落库缺省兜底（创建缺省形态：确认③ shard_total 默认 1 / timeout_ms 默认 60000 /
        // misfire 默认 FIRE_ONCE_NOW；部分更新遇到既有空值同样兜底，防 NOT NULL 违例）
        if (definition.getShardTotal() == null) {
            definition.setShardTotal(1);
        }
        // shardable 落库缺省兜底（REQ-20261122-150756 载体裁决⑥：列 NOT NULL DEFAULT false，
        // 创建缺省/部分更新既有空值统一 false=单发兼容，防 NOT NULL 违例）
        if (definition.getShardable() == null) {
            definition.setShardable(Boolean.FALSE);
        }
        if (definition.getTimeoutMs() == null) {
            definition.setTimeoutMs(DEFAULT_TIMEOUT_MS);
        }
        if (definition.getMisfirePolicy() == null) {
            definition.setMisfirePolicy(MisfirePolicy.FIRE_ONCE_NOW);
        }
        triggerCalculator.validate(definition);
        RetryPolicy.validate(definition.getRetryPolicy(), jsonMapper);
    }

    /** 视图映射（内部注册控制器复用；wire 形态与管理面一致）。 */
    public static JobView viewOf(SchJobDefinition definition) {
        return new JobView(String.valueOf(definition.getId()), definition.getJobKey(),
                definition.getAppName(), definition.getHandler(),
                definition.getTriggerType() == null ? null : definition.getTriggerType().name(),
                definition.getCronExpr(), definition.getFixedRateMs(), definition.getOnceFireAt(),
                definition.getParam(), definition.getRetryPolicy(), definition.getShardTotal(),
                definition.getShardable(), definition.getTimeoutMs(),
                definition.getMisfirePolicy() == null ? null : definition.getMisfirePolicy().name(),
                definition.getStatus() == null ? null : definition.getStatus().name(),
                definition.getNextFireTime(), definition.getRemark(), definition.getVersion());
    }
}
