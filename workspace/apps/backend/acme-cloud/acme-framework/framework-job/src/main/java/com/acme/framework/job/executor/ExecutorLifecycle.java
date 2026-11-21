package com.acme.framework.job.executor;

import com.acme.framework.job.config.JobProperties;
import com.acme.framework.job.support.JobHandlerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 执行器生命周期（REQ-20261121-200724 确认④）：服务就绪后经 {@link SchedulerClient} 向调度中心
 * 注册（appName/instanceId/host/port/handlers），随后按 {@code acme.job.executor.heartbeat-interval-ms}
 * （默认 10s ≤ stale 阈值一半）心跳续期。
 *
 * <p>降级口径（AC-4）：discovery 不可用（LB 缺席）或注册/心跳调用失败 → 静默降级——不注册不接单
 * （{@link #accepting()}=false，下发端点拒绝接单），本地 {@code @Scheduled} 兜底照跑；
 * 恢复后下一 tick 自动重注册（register/heartbeat 端点均幂等 upsert）。</p>
 */
public class ExecutorLifecycle implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ExecutorLifecycle.class);

    private final JobProperties properties;
    private final JobHandlerRegistry handlerRegistry;
    private final SchedulerClient schedulerClient;
    private final Environment environment;

    private final String instanceId = UUID.randomUUID().toString();
    private final String host = ExecutorIdentity.host();

    private volatile boolean registered;
    private volatile Integer port;
    private volatile ScheduledExecutorService heartbeatExecutor;

    public ExecutorLifecycle(JobProperties properties, JobHandlerRegistry handlerRegistry,
                             SchedulerClient schedulerClient, Environment environment) {
        this.properties = properties;
        this.handlerRegistry = handlerRegistry;
        this.schedulerClient = schedulerClient;
        this.environment = environment;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (heartbeatExecutor != null) {
            return;
        }
        if (!schedulerClient.isAddressable()) {
            // discovery 不可用（无 LB）：静默降级（AC-4）——不注册不接单，本地调度兜底
            log.warn("执行器降级（无服务发现客户端）：appName={} 不注册不接单，本地调度兜底照跑", appName());
            return;
        }
        port = resolvePort(event.getApplicationContext());
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "acme-job-executor");
            thread.setDaemon(true);
            return thread;
        });
        heartbeatExecutor = executor;
        executor.scheduleWithFixedDelay(this::tick,
                properties.getExecutor().getRegisterInitialDelayMs(),
                properties.getExecutor().getHeartbeatIntervalMs(), TimeUnit.MILLISECONDS);
    }

    /** 单 tick：未注册→注册；已注册→心跳；失败→降级（恢复后自动重注册，AC-4）。 */
    void tick() {
        try {
            List<String> handlers = handlerRegistry.keys();
            if (registered) {
                schedulerClient.heartbeat(appName(), instanceId, handlers);
            } else {
                schedulerClient.register(appName(), instanceId, host, port, handlers);
                registered = true;
                log.info("执行器注册成功：appName={}, instanceId={}, host={}, port={}, handlers={}",
                        appName(), instanceId, host, port, handlers);
            }
        } catch (RuntimeException ex) {
            registered = false;
            if (log.isDebugEnabled()) {
                log.debug("执行器注册/心跳失败（静默降级，恢复后自动重注册）: appName={}, instanceId={}, error={}",
                        appName(), instanceId, ex.getMessage());
            } else {
                log.warn("执行器注册/心跳失败（静默降级，恢复后自动重注册）: appName={}, error={}",
                        appName(), ex.getMessage());
            }
        }
    }

    /** 是否可接单（已注册在线）；false=下发端点拒绝接单。 */
    public boolean accepting() {
        return registered;
    }

    /** 实例身份 appName（Nacos 服务名）。 */
    public String appName() {
        return ExecutorIdentity.appName(properties, environment);
    }

    /** 实例 id（UUID）。 */
    public String instanceId() {
        return instanceId;
    }

    /** 自报端口（web 服务器实际端口优先；非 web 形态取 server.port 配置，再缺省 null）。 */
    Integer resolvePort(org.springframework.context.ApplicationContext applicationContext) {
        if (applicationContext instanceof WebServerApplicationContext webContext) {
            int serverPort = webContext.getWebServer().getPort();
            if (serverPort > 0) {
                return serverPort;
            }
        }
        String configured = environment.getProperty("server.port");
        return configured == null || configured.isBlank() ? null : Integer.valueOf(configured.trim());
    }

    @Override
    public void destroy() {
        ScheduledExecutorService executor = heartbeatExecutor;
        heartbeatExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
