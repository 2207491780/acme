package com.acme.platform.system.api;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 登录认证画像查询契约（架构设计 3.3、Q-D06 同步 Feign；M-07 链深 3 压线合规）：
 * platform-auth 以服务身份主体调用（Q-D08）；查无此用户由服务端返回 404（错误信封），
 * 消费方经 framework-feign 错误解码捕获后统一映射 LOGIN_FAILED（FR-005 不区分账号存在性）。
 */
@FeignClient(name = "platform-system", contextId = "authProfileClient")
public interface AuthProfileClient {

    /**
     * 按用户名查认证画像（全局唯一键定位）。
     *
     * @param username 用户名
     * @return 信封携带画像；用户不存在时响应 404（不经本类型解码）
     */
    @GetMapping("/internal/v1/auth-profiles")
    ContractResult<AuthProfileView> findByUsername(@RequestParam("username") String username);

    /**
     * 按 userId 批量查认证画像（REQ-20261114-120105：在线用户列表 username/超管判定富化；
     * 内部支撑端点——不存在与画像缺失的 userId 不在结果中，消费方按缺省展示处理）。
     *
     * @param userIds 用户 ID 集（去重后查询；空集返回空列表）
     * @return 信封携带命中的画像列表
     */
    @GetMapping("/internal/v1/auth-profiles/batch")
    ContractResult<List<AuthProfileView>> findByUserIds(@RequestParam("userIds") List<Long> userIds);
}
