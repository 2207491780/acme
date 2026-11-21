package com.acme.framework.job.executor;

import org.springframework.core.env.Environment;

/**
 * 执行器实例身份解析（确认④）：appName=Nacos 服务名（spring.application.name，可经
 * acme.job.executor.app-name 覆盖）、instanceId=UUID（进程内唯一，重启生成新值）、
 * host/port 自报（port 供运维面展示/直连兜底，下发按 Nacos 寻址）。
 */
public final class ExecutorIdentity {

    private ExecutorIdentity() {
    }

    /** appName 解析（acme.job.executor.app-name 覆盖优先；空=spring.application.name）。 */
    public static String appName(com.acme.framework.job.config.JobProperties properties, Environment environment) {
        String override = properties.getExecutor().getAppName();
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        String appName = environment.getProperty("spring.application.name");
        return appName == null ? "" : appName.trim();
    }

    /** host 自报（解析失败回环兜底，不阻断启动）。 */
    public static String host() {
        try {
            String host = java.net.InetAddress.getLocalHost().getHostAddress();
            return host == null || host.isBlank() ? "127.0.0.1" : host;
        } catch (RuntimeException | java.net.UnknownHostException ex) {
            return "127.0.0.1";
        }
    }
}
