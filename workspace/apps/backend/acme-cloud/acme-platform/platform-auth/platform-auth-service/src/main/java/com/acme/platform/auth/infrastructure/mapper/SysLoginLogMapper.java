package com.acme.platform.auth.infrastructure.mapper;

import com.acme.platform.auth.domain.SysLoginLog;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 登录日志 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface SysLoginLogMapper extends BaseMapper<SysLoginLog> {

    /**
     * 按日聚合登录日志条数（REQ-20261119-011524 确认记录②，GET /internal/v1/login-log-stats 数据源）：
     * 自 from 起按 created_at 所在日（本地时区语义，PG to_char）group by，channel 不分（成功+失败全量）。
     * 平台级表无 tenant_id（Q-D04 同型）无需租户过滤；deleted=false 与 Flex 默认查询面对齐
     * （保留期软删行不计入统计）。行键取列别名 stat_date / stat_count（PG 未加引号别名折叠为小写）。
     *
     * @param from 统计窗口下界（含；由调用方按 days 折算，避免 SQL 内联日期方言运算）
     * @return 按日升序聚合行（date=YYYY-MM-DD 字符串、count=bigint）
     */
    @Select("""
            select to_char(created_at, 'YYYY-MM-DD') as stat_date, count(*) as stat_count
            from sys_login_log
            where deleted = false and created_at >= #{from}
            group by stat_date
            order by stat_date
            """)
    List<Map<String, Object>> selectDailyStats(@Param("from") Instant from);
}
