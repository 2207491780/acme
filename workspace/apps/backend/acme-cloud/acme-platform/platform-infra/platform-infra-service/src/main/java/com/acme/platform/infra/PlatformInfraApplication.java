package com.acme.platform.infra;

import com.acme.platform.infra.config.LoginLogRetentionProperties;
import com.acme.platform.infra.internal.client.LoginLogCleanupClient;
import com.acme.platform.system.api.AuthorizationClient;
import com.acme.platform.system.api.DataScopeClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 基础设施域平台服务启动入口（platform-infra-audit 拆分评审结论；18.1.1 第 5 步）。
 * 模块名 = 应用名 = 配置中心标识（5.4.3）：platform-infra。
 * 权限判定（接口级与数据权限）与数据范围解析经 platform-system AuthorizationClient /
 * DataScopeClient（4.4.2 仅经 *-api；REQ-20261114-120104）。
 */
@SpringBootApplication
@EnableConfigurationProperties(LoginLogRetentionProperties.class)
@EnableFeignClients(clients = {AuthorizationClient.class, DataScopeClient.class, LoginLogCleanupClient.class})
public class PlatformInfraApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformInfraApplication.class, args);
    }
}
