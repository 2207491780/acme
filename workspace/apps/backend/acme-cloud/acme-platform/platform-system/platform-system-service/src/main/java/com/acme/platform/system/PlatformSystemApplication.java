package com.acme.platform.system;

import com.acme.platform.auth.api.TenantSessionRevokeClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 系统管理域平台服务启动入口（G2.5 拆分评审结论；18.1.1 第 5 步）。
 * 模块名 = 应用名 = 配置中心标识（5.4.3）：platform-system；
 * REQ-20261117-025200 增补 system → auth 单向撤销契约消费（显式 clients 注册，同 PlatformAuthApplication 口径）。
 */
@SpringBootApplication
@EnableFeignClients(clients = {TenantSessionRevokeClient.class})
public class PlatformSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformSystemApplication.class, args);
    }
}
