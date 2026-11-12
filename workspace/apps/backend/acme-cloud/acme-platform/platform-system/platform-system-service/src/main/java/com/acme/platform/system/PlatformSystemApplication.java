package com.acme.platform.system;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 系统管理域平台服务启动入口（G2.5 拆分评审结论；18.1.1 第 5 步）。
 * 模块名 = 应用名 = 配置中心标识（5.4.3）：platform-system。
 */
@SpringBootApplication
public class PlatformSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformSystemApplication.class, args);
    }
}
