package com.acme.platform.system.api;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

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
}
