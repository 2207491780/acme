package com.acme.platform.scheduler.interfaces;

import com.acme.common.core.Result;
import com.acme.platform.scheduler.api.ExecutorHeartbeatRequest;
import com.acme.platform.scheduler.api.ExecutorRegisterRequest;
import com.acme.platform.scheduler.application.ExecutorUseCase;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 执行器面内部接口（设计规格 §5 managed 模式服务侧；不经网关对外，openapi 不登记）：
 * register / heartbeat（无权限点，Result 信封；服务间调用经 framework-security 内部令牌校验 11.6.1）。
 * JOB-2 执行器 SDK 对接契约——路径与 DTO 字段名不得变更。
 */
@RestController
public class InternalExecutorController {

    private final ExecutorUseCase executorUseCase;

    public InternalExecutorController(ExecutorUseCase executorUseCase) {
        this.executorUseCase = executorUseCase;
    }

    /** 执行器注册（幂等 upsert：uk(app_name, instance_id)；AC-10 注册入库）。 */
    @PostMapping("/internal/v1/scheduler/executors/register")
    public Result<Void> register(@RequestBody ExecutorRegisterRequest request) {
        executorUseCase.register(request);
        return Result.ok(null);
    }

    /** 执行器心跳（10s 周期；刷新 heartbeat_at；未知实例按注册 upsert 自愈，AC-10）。 */
    @PostMapping("/internal/v1/scheduler/executors/heartbeat")
    public Result<Void> heartbeat(@RequestBody ExecutorHeartbeatRequest request) {
        executorUseCase.heartbeat(request);
        return Result.ok(null);
    }
}
