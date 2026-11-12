package com.acme.business.content;

import com.acme.platform.system.api.AuthorizationClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 内容域业务服务启动入口（business-api-integration G2.5 拆分评审通过；18.1.1 第 5 步）。
 * 模块名 = 应用名 = 配置中心标识（5.4.3）：business-content；
 * 服务间依赖仅 platform-system-api（content → system 单向，4.4.2）。
 */
@SpringBootApplication
@EnableFeignClients(clients = {AuthorizationClient.class})
public class BusinessContentApplication {

    public static void main(String[] args) {
        SpringApplication.run(BusinessContentApplication.class, args);
    }
}
