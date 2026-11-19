package com.acme.platform.infra.infrastructure.mapper;

import com.acme.platform.infra.domain.OpAuditEvent;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 审计事件 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface OpAuditEventMapper extends BaseMapper<OpAuditEvent> {

    /**
     * 审计动作分布聚合（REQ-20261119-011524 确认记录②，GET /internal/v1/audit-stats/distribution
     * 数据源）：自 from 起 group by action 按条数降序取 top N。跨租户平台口径——原生 SQL 无自动
     * 租户注入，跨租户旁路声明与审计由调用方用例 {@code @IgnoreTenant} 承担（12.4.2 口径，
     * AuditRetentionCleanupUseCase 同型）；deleted=false 与 Flex 默认查询面对齐（事件行不可变，
     * deleted 恒 false，谓词防御保留期软删面回填）。行键取列别名 stat_action / stat_count
     * （PG 未加引号别名折叠为小写）。
     *
     * @param from 统计窗口下界（含；由调用方按 days 折算，避免 SQL 内联日期方言运算）
     * @param top  截断条数（LIMIT 参数）
     * @return 按条数降序聚合行（action、count=bigint）
     */
    @Select("""
            select action as stat_action, count(*) as stat_count
            from acme_op_audit
            where deleted = false and occurred_at >= #{from}
            group by action
            order by stat_count desc
            limit #{top}
            """)
    List<Map<String, Object>> selectActionDistribution(@Param("from") Instant from,
                                                       @Param("top") int top);
}
