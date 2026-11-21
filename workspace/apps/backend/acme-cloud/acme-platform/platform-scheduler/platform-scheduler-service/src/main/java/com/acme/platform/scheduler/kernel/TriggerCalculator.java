package com.acme.platform.scheduler.kernel;

import com.acme.common.core.BizException;
import com.acme.platform.scheduler.SchedulerError;
import com.acme.platform.scheduler.api.MisfirePolicy;
import com.acme.platform.scheduler.api.TriggerType;
import com.acme.platform.scheduler.config.SchedulerProperties;
import com.acme.platform.scheduler.domain.SchJobDefinition;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * 触发点计算器（设计规格 §3.1：cron 解析 Spring {@code CronExpression} 6 位域，零新依赖；
 * FIXED_RATE 触发后即计算下一轮；ONCE 单发）。
 * 校验与推进同源：{@link #validate} 供创建/更新入口、内核推进共用，杜绝两套口径漂移。
 *
 * <p>misfire 判界口径（实现解释，报告已列回改建议）：周期型到期点 T 的「下一期」也已过期
 * （整周期错过，nextAfter(T) &lt;= now）才进入 misfire 分支——单 tick 内迟到（下一期未到）
 * 一律按正常触发；否则秒级 tick 延迟（fixedDelay=1s）会被 IGNORE 策略误判为「错过」致任务
 * 永不触发。ONCE 无下一期，以 tick 容差（acme.scheduler.kernel.misfire-grace-ms，缺省 1s）
 * 判界：超容差错过按策略处置，容差内按正常触发。</p>
 */
@Component
public class TriggerCalculator {

    /** 极小 fixed_rate 配长停机窗口的推进防御上限。 */
    private static final int ADVANCE_GUARD = 100_000;

    private final SchedulerProperties properties;

    public TriggerCalculator(SchedulerProperties properties) {
        this.properties = properties;
    }

    /**
     * 校验触发参数（创建/更新入口；失败抛 SCHEDULER_TRIGGER_INVALID——CRON 语法错 /
     * FIXED_RATE 非正数 / ONCE 缺 onceFireAt / 类型缺参）。
     */
    public void validate(SchJobDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        TriggerType type = definition.getTriggerType();
        if (type == null) {
            throw new BizException(SchedulerError.SCHEDULER_TRIGGER_INVALID, "triggerType 不能为空");
        }
        switch (type) {
            case CRON -> {
                if (definition.getCronExpr() == null || definition.getCronExpr().isBlank()
                        || !CronExpression.isValidExpression(definition.getCronExpr().trim())) {
                    throw new BizException(SchedulerError.SCHEDULER_TRIGGER_INVALID,
                            "CRON 表达式非法（Spring CronExpression 6 位域）: " + definition.getCronExpr());
                }
            }
            case FIXED_RATE -> {
                if (definition.getFixedRateMs() == null || definition.getFixedRateMs() <= 0) {
                    throw new BizException(SchedulerError.SCHEDULER_TRIGGER_INVALID,
                            "FIXED_RATE 间隔必须为正数毫秒: " + definition.getFixedRateMs());
                }
            }
            case ONCE -> {
                if (definition.getOnceFireAt() == null) {
                    throw new BizException(SchedulerError.SCHEDULER_TRIGGER_INVALID,
                            "ONCE 触发必须提供 onceFireAt");
                }
            }
        }
    }

    /**
     * 初始排期（启动恢复 / 创建即启用）：CRON=下一表达式命中点；FIXED_RATE=now（Spring
     * {@code @Scheduled(fixedRate)} 语义——启用/恢复即首发，后续每次触发后即时计算下一轮）；
     * ONCE=onceFireAt。CRON 经 ZonedDateTime 计算（Spring CronExpression 需要 DayOfWeek 等
     * 日历字段，Instant 不支持），时区取系统默认（调度语义按部署时区；触发点落库仍为 UTC Instant）。
     */
    public Instant initialFireTime(SchJobDefinition definition, Instant now) {
        return switch (definition.getTriggerType()) {
            case CRON -> cronNext(definition.getCronExpr(), now);
            case FIXED_RATE -> now;
            case ONCE -> definition.getOnceFireAt();
        };
    }

    /**
     * 单步下一触发点（自 from 严格向后；ONCE 无后续返回 null）。
     */
    public Instant nextAfter(SchJobDefinition definition, Instant from) {
        return switch (definition.getTriggerType()) {
            case CRON -> cronNext(definition.getCronExpr(), from);
            case FIXED_RATE -> from.plusMillis(definition.getFixedRateMs());
            case ONCE -> null;
        };
    }

    /** CRON 下一点（ZonedDateTime 承载日历字段；表达式的秒/分粒度在 toInstant 后无损回 Instant）。 */
    private static Instant cronNext(String cronExpr, Instant from) {
        ZonedDateTime next = CronExpression.parse(cronExpr.trim())
                .next(from.atZone(ZoneId.systemDefault()));
        return next == null ? null : next.toInstant();
    }

    /**
     * misfire 处置（确认⑤两策略）：错过判界——周期型（CRON/FIXED_RATE）下一期也已过期（整周期错过）
     * 或 ONCE 超出 tick 容差（graceMs，缺省 1s≈一个 tick 周期，秒级调度抖动不误判）；判为错过时
     * FIRE_ONCE_NOW（缺省，含策略缺失防御）补跑、IGNORE 放弃；未判为错过一律正常触发。
     * ONCE + IGNORE + 错过 → 不补跑且无下一期（等价跳过丢弃，规格「跳到下次」语义）。
     *
     * @param missedFireTime 已过期的排期点 T
     * @param now            当前时刻
     * @return 处置结果（fireNow=false 即 IGNORE 不补跑）
     */
    public MisfireDecision resolveMisfire(SchJobDefinition definition, Instant missedFireTime, Instant now) {
        Instant nextPeriod = nextAfter(definition, missedFireTime);
        long graceMs = properties.getKernel().getMisfireGraceMs();
        boolean wholePeriodMissed = nextPeriod != null && !nextPeriod.isAfter(now)
                || (nextPeriod == null && missedFireTime.isBefore(now.minusMillis(graceMs)));
        boolean fire = !wholePeriodMissed
                || definition.getMisfirePolicy() == null
                || definition.getMisfirePolicy() == MisfirePolicy.FIRE_ONCE_NOW;
        return new MisfireDecision(missedFireTime, fire);
    }

    /**
     * 从 from 起推进到第一个未来触发点（跳过全部已过期槽位；ONCE 返回 null）。
     */
    public Instant firstFutureFireTime(SchJobDefinition definition, Instant from, Instant now) {
        Instant cursor = from;
        Instant next = nextAfter(definition, cursor);
        int guard = 0;
        while (next != null && !next.isAfter(now)) {
            cursor = next;
            next = nextAfter(definition, cursor);
            if (++guard > ADVANCE_GUARD) {
                return now;
            }
        }
        return next;
    }

    /** misfire 处置结果。 */
    public record MisfireDecision(Instant fireTime, boolean fireNow) {
    }
}
