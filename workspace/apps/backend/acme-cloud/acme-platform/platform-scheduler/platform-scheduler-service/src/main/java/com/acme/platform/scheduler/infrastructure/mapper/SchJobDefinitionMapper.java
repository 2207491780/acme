package com.acme.platform.scheduler.infrastructure.mapper;

import com.acme.platform.scheduler.api.MisfirePolicy;
import com.acme.platform.scheduler.api.SchedulerJobStatus;
import com.acme.platform.scheduler.api.TriggerType;
import com.acme.platform.scheduler.domain.SchJobDefinition;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

/**
 * 任务定义 mapper（@Mapper：standalone 经 starter 按 AutoConfigurationPackages 注册主包；
 * 单体经 MonolithApplication @MapperScan(annotationClass=Mapper.class) 收编，REQ-20261121-071002 确认⑥）。
 */
@Mapper
public interface SchJobDefinitionMapper extends BaseMapper<SchJobDefinition> {

    /**
     * 软删同键行 id 查找（REQ-20261127-235505 round=1 方向 A）：原生 SQL 绕 Flex 逻辑删——
     * BaseMapper 生成查询自动追加 {@code deleted=false}，看不到软删行；
     * uk_sch_job_definition_key 全列唯一 ⇒ 同 job_key 至多一行，deleted=true 即注销残留。
     * 无软删行返回 null。
     */
    @Select("""
            select id from sch_job_definition
            where job_key = #{jobKey} and deleted = true
            """)
    Long findSoftDeletedIdByJobKey(@Param("jobKey") String jobKey);

    /**
     * 复活软删行（REQ-20261127-235505 round=1 方向 A）：UPDATE 而非 INSERT——软删行仍占用
     * uk_sch_job_definition_key 全列唯一，直接 INSERT 撞约束 500（SYS-00-0006）。
     * 字段写值与 {@code SchedulerKernel.registerOneShot} INSERT 路径完全同款（请求字段全量覆盖、
     * TriggerType=ONCE / status=ENABLED / next_fire_time=onceFireAt / shardable=false /
     * shard_total=1 / misfire=FIRE_ONCE_NOW / 重试策略缺省），并清空 cron_expr/fixed_rate_ms/
     * remark/tenant_id 对齐新建 INSERT 的空缺省形态；created_at/created_by 保留原始创建面。
     * 关键坑：Flex 逻辑删使常规 updateById/updateByQuery 对软删行匹配 0 行——必须原生 UPDATE。
     * 审计三件套在 SQL 内显式维护（AuditFillListener 对自定义 SQL 不生效）：updated_at/updated_by
     * 由调用方传值（口径同 AuditFillListener），version SQL 端自增。jsonb 列经
     * {@code cast(#{...} as jsonb)} 装载（直 setString 被 PG 类型推断拒绝，与 JsonbTypeHandler
     * 同源口径）。枚举以参数传递，走与既有写入面一致的 type handler（按 name 存储）。
     * 并发护栏：WHERE 带 {@code AND deleted = true}——并发双复活时后到者匹配 0 行，由调用方按
     * SCHEDULER_JOB_KEY_DUPLICATE 收敛（workflow 客户端撞键幂等，协议安全）。
     *
     * @return 命中行数（1=复活成功；0=无软删行或并发已被他方复活）
     */
    @Update("""
            update sch_job_definition
            set deleted = false,
                app_name = #{appName},
                handler = #{handler},
                trigger_type = #{triggerType},
                cron_expr = null,
                fixed_rate_ms = null,
                once_fire_at = #{onceFireAt},
                param = cast(#{param} as jsonb),
                retry_policy = cast(#{retryPolicy} as jsonb),
                shard_total = 1,
                shardable = false,
                timeout_ms = #{timeoutMs},
                misfire_policy = #{misfirePolicy},
                status = #{status},
                next_fire_time = #{onceFireAt},
                tenant_id = null,
                remark = null,
                updated_at = #{updatedAt},
                updated_by = #{updatedBy},
                version = version + 1
            where job_key = #{jobKey} and deleted = true
            """)
    int reviveSoftDeletedByJobKey(@Param("jobKey") String jobKey,
                                  @Param("appName") String appName,
                                  @Param("handler") String handler,
                                  @Param("triggerType") TriggerType triggerType,
                                  @Param("onceFireAt") Instant onceFireAt,
                                  @Param("param") String param,
                                  @Param("retryPolicy") String retryPolicy,
                                  @Param("timeoutMs") Long timeoutMs,
                                  @Param("misfirePolicy") MisfirePolicy misfirePolicy,
                                  @Param("status") SchedulerJobStatus status,
                                  @Param("updatedAt") Instant updatedAt,
                                  @Param("updatedBy") String updatedBy);
}
