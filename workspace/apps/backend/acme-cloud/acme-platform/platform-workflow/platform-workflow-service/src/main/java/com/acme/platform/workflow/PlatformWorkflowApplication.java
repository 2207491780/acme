package com.acme.platform.workflow;

import com.acme.platform.scheduler.api.SchedulerJobClient;
import com.acme.platform.system.api.AuthorizationClient;
import com.acme.platform.system.api.WorkflowIdentityClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * BPM 工作流域平台服务启动入口（REQ-20261121-142316；设计规格 §1 布局终稿）。
 * 模块名 = 应用名 = 配置中心标识（5.4.3）：platform-workflow；端口 9883。
 * 显式 clients 注册（PlatformSchedulerApplication 口径）：权限判定 AuthorizationClient、
 * workflow 身份目录 WorkflowIdentityClient（UserTask 候选解析）、一次性定时任务注册
 * SchedulerJobClient（确认⑥定时边界联动）。
 * 命令内核装配：WorkflowEngineConfiguration（责任链 + 行为注册表 + 编译缓存）随组件扫描生效。
 */
@SpringBootApplication
@EnableFeignClients(clients = {AuthorizationClient.class, WorkflowIdentityClient.class,
        SchedulerJobClient.class})
public class PlatformWorkflowApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformWorkflowApplication.class, args);
    }
}
