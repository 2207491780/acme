package com.acme.platform.scheduler;

import com.acme.platform.system.api.AuthorizationClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 调度中心域平台服务启动入口（REQ-20261121-071002；设计规格 §1 布局终稿）。
 * 模块名 = 应用名 = 配置中心标识（5.4.3）：platform-scheduler；端口 9884。
 * 显式 clients 注册（PlatformSystemApplication 口径）：权限判定跨域契约消费（AuthorizationClient）。
 * 调度内核装配：SchedulerSchedulingConfig（@EnableScheduling + @EnableSchedulerLock）随组件扫描生效。
 */
@SpringBootApplication
@EnableFeignClients(clients = {AuthorizationClient.class})
public class PlatformSchedulerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformSchedulerApplication.class, args);
    }
}
