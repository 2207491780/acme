package com.acme.platform.auth.infrastructure.repository;

import com.acme.common.contract.PageResult;
import com.acme.framework.flex.internal.FlexDeletes;
import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.platform.auth.api.LoginLogStatsPoint;
import com.acme.platform.auth.domain.SysLoginLog;
import com.acme.platform.auth.infrastructure.mapper.SysLoginLogMapper;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 登录日志仓库实现（MyBatis-Flex 底座；分页经 paginate 内部 count，排序仅列表查询携带）。
 * 逻辑删除语义：deleteByQuery=置 deleted 标记（MyBatis-Flex 逻辑删除），物理删除走 FlexDeletes 旁路。
 */
@Repository
public class LoginLogRepositoryImpl extends FlexRepositorySupport<SysLoginLog>
        implements LoginLogRepository {

    public LoginLogRepositoryImpl(SysLoginLogMapper mapper) {
        super(mapper);
    }

    @Override
    public PageResult<SysLoginLog> page(Integer page, Integer size, String username, String userType,
                                        String result, Instant from, Instant to) {
        QueryWrapper query = QueryWrapper.create();
        if (username != null && !username.isBlank()) {
            query.and(SysLoginLog::getUsername).like(username.trim());
        }
        if (userType != null && !userType.isBlank()) {
            query.and(SysLoginLog::getUserType).eq(userType.trim());
        }
        if (result != null && !result.isBlank()) {
            query.and(SysLoginLog::getResult).eq(result.trim());
        }
        if (from != null) {
            query.and(SysLoginLog::getCreatedAt).ge(from);
        }
        if (to != null) {
            query.and(SysLoginLog::getCreatedAt).le(to);
        }
        query.orderBy(SysLoginLog::getCreatedAt, false);
        return findAll(query, page, size);
    }

    @Override
    public int forceDeleteSoftDeletedExpiredBefore(Instant cutoff) {
        return forceDeleteByQuery(QueryWrapper.create()
                .where(new QueryColumn("deleted").eq(true))
                .and(new QueryColumn("created_at").lt(cutoff)));
    }

    @Override
    public List<LoginLogStatsPoint> dailyStats(Instant from) {
        return ((SysLoginLogMapper) mapper()).selectDailyStats(from).stream()
                .map(LoginLogRepositoryImpl::toStatsPoint)
                .toList();
    }

    /** 聚合行 → 统计点（列别名 stat_date / stat_count；PG 未加引号别名折叠为小写，忽略大小写取键）。 */
    private static LoginLogStatsPoint toStatsPoint(Map<String, Object> row) {
        String date = stringValue(row, "stat_date");
        long count = numberValue(row, "stat_count");
        return new LoginLogStatsPoint(date, count);
    }

    /** 忽略大小写取字符串列（别名折叠防分歧）。 */
    private static String stringValue(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)
                    && entry.getValue() != null) {
                return String.valueOf(entry.getValue());
            }
        }
        return null;
    }

    /** 忽略大小写取数值列（PG bigint → Long）。 */
    private static long numberValue(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)
                    && entry.getValue() instanceof Number number) {
                return number.longValue();
            }
        }
        return 0L;
    }
}
