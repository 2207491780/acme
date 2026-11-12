package com.acme.platform.infra;

import com.acme.platform.system.api.AuthorizationClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 基础设施域平台服务启动入口（platform-infra-audit 拆分评审结论；18.1.1 第 5 步）。
 * 模块名 = 应用名 = 配置中心标识（5.4.3）：platform-infra。
 * 权限判定（接口级与数据权限）经 platform-system AuthorizationClient（4.4.2 仅经 *-api）。
 */
@SpringBootApplication
@EnableFeignClients(clients = {AuthorizationClient.class})
public class PlatformInfraApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformInfraApplication.class, args);
    }
}
