package com.acme.platform.scheduler.domain;

import com.acme.framework.flex.support.AuditBase;
import com.acme.platform.scheduler.api.ExecutorStatus;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

import java.time.Instant;

/**
 * 执行器注册（契约确认③：sch_executor_registry；uk(app_name, instance_id) 由 DDL 承载；
 * 注册/心跳幂等 upsert，心跳 stale 阈值 30s → OFFLINE）。
 */
@Table("sch_executor_registry")
public class SchExecutorRegistry extends AuditBase {

    /** 执行器所属应用（Nacos 服务名）。 */
    @Column("app_name")
    private String appName;

    /** 实例标识（进程内唯一）。 */
    @Column("instance_id")
    private String instanceId;

    /** 主机地址。 */
    @Column("host")
    private String host;

    /** 服务端口（可空）。 */
    @Column("port")
    private Integer port;

    /** handler 清单（jsonb 数组文本）。 */
    @Column(value = "handlers", typeHandler = com.acme.platform.scheduler.infrastructure.mapper.JsonbTypeHandler.class)
    private String handlers;

    /** 最近心跳时刻。 */
    @Column("heartbeat_at")
    private Instant heartbeatAt;

    /** 在线状态（ONLINE / OFFLINE；失联巡检回写 + 视图按阈值动态判定双口径）。 */
    @Column("status")
    private ExecutorStatus status;

    public SchExecutorRegistry() {
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getHandlers() {
        return handlers;
    }

    public void setHandlers(String handlers) {
        this.handlers = handlers;
    }

    public Instant getHeartbeatAt() {
        return heartbeatAt;
    }

    public void setHeartbeatAt(Instant heartbeatAt) {
        this.heartbeatAt = heartbeatAt;
    }

    public ExecutorStatus getStatus() {
        return status;
    }

    public void setStatus(ExecutorStatus status) {
        this.status = status;
    }
}
