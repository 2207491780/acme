package com.acme.platform.scheduler.application;

import com.acme.common.core.Clock;
import com.acme.platform.scheduler.api.ExecutorRegisterRequest;
import com.acme.platform.scheduler.api.ExecutorStatus;
import com.acme.platform.scheduler.config.SchedulerProperties;
import com.acme.platform.scheduler.domain.SchExecutorRegistry;
import com.acme.platform.scheduler.infrastructure.repository.ExecutorRegistryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * 执行器面用例（设计规格 §5 managed 模式服务侧；AC-10）：
 * 注册幂等 upsert（uk(app_name, instance_id)）、心跳刷新（未知实例按注册语义 upsert 自愈——
 * 失联重启场景注册行已存在；全新实例心跳等同注册）、列表含动态在线状态判定。
 */
@Service
public class ExecutorUseCase {

    private static final Logger log = LoggerFactory.getLogger(ExecutorUseCase.class);

    private final ExecutorRegistryRepository registryRepository;
    private final SchedulerProperties properties;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public ExecutorUseCase(ExecutorRegistryRepository registryRepository,
                           SchedulerProperties schedulerProperties,
                           JsonMapper jsonMapper,
                           Clock schedulerClock) {
        this.registryRepository = registryRepository;
        this.properties = schedulerProperties;
        this.jsonMapper = jsonMapper;
        this.clock = schedulerClock;
    }

    /** 注册（幂等 upsert：已存在实例刷新心跳/元数据；新实例落库 ONLINE）。 */
    public void register(ExecutorRegisterRequest request) {
        Instant now = clock.now();
        SchExecutorRegistry existing = registryRepository
                .findByAppAndInstance(request.appName(), request.instanceId()).orElse(null);
        if (existing == null) {
            SchExecutorRegistry registry = new SchExecutorRegistry();
            registry.setAppName(request.appName());
            registry.setInstanceId(request.instanceId());
            registry.setHost(request.host());
            registry.setPort(request.port());
            registry.setHandlers(toHandlersJson(request.handlers()));
            registry.setHeartbeatAt(now);
            registry.setStatus(ExecutorStatus.ONLINE);
            registryRepository.save(registry);
            log.info("执行器注册：app={}, instance={}, handlers={}",
                    request.appName(), request.instanceId(), request.handlers());
            return;
        }
        existing.setHost(request.host());
        existing.setPort(request.port());
        if (request.handlers() != null) {
            existing.setHandlers(toHandlersJson(request.handlers()));
        }
        existing.setHeartbeatAt(now);
        existing.setStatus(ExecutorStatus.ONLINE);
        registryRepository.save(existing);
        log.debug("执行器重复注册（刷新）：app={}, instance={}", request.appName(), request.instanceId());
    }

    /** 心跳（刷新 heartbeat_at + 状态 ONLINE；未知实例按注册语义 upsert 自愈）。 */
    public void heartbeat(com.acme.platform.scheduler.api.ExecutorHeartbeatRequest request) {
        Instant now = clock.now();
        SchExecutorRegistry existing = registryRepository
                .findByAppAndInstance(request.appName(), request.instanceId()).orElse(null);
        if (existing == null) {
            log.info("心跳未知实例（按注册 upsert 自愈）：app={}, instance={}", request.appName(), request.instanceId());
            register(new ExecutorRegisterRequest(request.appName(), request.instanceId(), "",
                    null, request.handlers()));
            return;
        }
        if (request.handlers() != null) {
            existing.setHandlers(toHandlersJson(request.handlers()));
        }
        existing.setHeartbeatAt(now);
        existing.setStatus(ExecutorStatus.ONLINE);
        registryRepository.save(existing);
    }

    /** 执行器清单（含动态在线状态：心跳 stale 阈值 30s → OFFLINE，AC-10）。 */
    public List<ExecutorView> list() {
        Instant now = clock.now();
        Instant freshBefore = now.minusSeconds(properties.getExecutor().getHeartbeatStaleSeconds());
        return registryRepository.findAll().stream()
                .sorted(Comparator.comparing(SchExecutorRegistry::getAppName)
                        .thenComparing(SchExecutorRegistry::getInstanceId))
                .map(registry -> toView(registry, freshBefore))
                .toList();
    }

    /** 应用下在线实例（内核下发寻址口径与本用例共用阈值）。 */
    public List<ExecutorView> listByApp(String appName) {
        Instant now = clock.now();
        Instant freshBefore = now.minusSeconds(properties.getExecutor().getHeartbeatStaleSeconds());
        return registryRepository.findByAppName(appName).stream()
                .map(registry -> toView(registry, freshBefore))
                .toList();
    }

    private ExecutorView toView(SchExecutorRegistry registry, Instant freshBefore) {
        boolean fresh = registry.getHeartbeatAt() != null && registry.getHeartbeatAt().isAfter(freshBefore);
        String status = (fresh && registry.getStatus() == ExecutorStatus.ONLINE)
                ? ExecutorStatus.ONLINE.name() : ExecutorStatus.OFFLINE.name();
        return new ExecutorView(String.valueOf(registry.getId()), registry.getAppName(),
                registry.getInstanceId(), registry.getHost(), registry.getPort(),
                fromHandlersJson(registry.getHandlers()), registry.getHeartbeatAt(), status);
    }

    private String toHandlersJson(List<String> handlers) {
        if (handlers == null) {
            return null;
        }
        return jsonMapper.writeValueAsString(handlers);
    }

    private List<String> fromHandlersJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return jsonMapper.readValue(json, new tools.jackson.core.type.TypeReference<List<String>>() {
            });
        } catch (RuntimeException ex) {
            return List.of();
        }
    }
}
