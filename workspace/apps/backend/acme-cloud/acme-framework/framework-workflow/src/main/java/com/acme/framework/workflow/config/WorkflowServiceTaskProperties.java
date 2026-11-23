package com.acme.framework.workflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * BPM 集成 SDK 配置属性（前缀 {@code acme.workflow.service-task}；全字段默认值，Backend.md 8.1.3）。
 */
@ConfigurationProperties(prefix = "acme.workflow.service-task")
public class WorkflowServiceTaskProperties {

    /** 接收端点映射前缀（业务 BPMN 节点 acme:callbackPath 须落在该前缀下；缺省 internal 面）。 */
    private String receivePathPrefix = "/internal/v1/service-tasks";

    /**
     * workflow 寻址显式直连 base-url（单体形态 {@code http://127.0.0.1:${local.server.port:${server.port}}}）。
     *
     * <p>⚠️ 占位符解析时序（TL round-2 diagnostic）：{@code @Value} 构造期与 {@code @ConfigurationProperties}
     * 绑定期（均早于 Web 服务器启动）同样会把 {@code ${local.server.port}} 烘焙为字面 {@code :0}——
     * 含 RANDOM_PORT 运行期占位符的键值只能经 Environment 发送期原始读取+解析消费
     * （WorkflowServiceTaskCallbackClient.resolveBase 口径；LeavePilotProcessDeployer 部署期惰性
     * 读取同款），禁止经本 bean 值/@Value 链消费。空=LoadBalancer 按 workflow-service-name 寻址。</p>
     */
    private String callbackBaseUrl = "";

    /** workflow 服务名（LoadBalancer 寻址键；缺省 platform-workflow）。 */
    private String workflowServiceName = "platform-workflow";

    /** 回调主体服务名（X-Acme-Token sub=业务服务名；空=取 spring.application.name）。 */
    private String serviceName = "";

    /**
     * 接收端点异步处理模式（false=同步：handler+回调内联下发请求线程，引擎重试/挂起语义原样；
     * true=下发受理即 2xx，handler 与回调经调度器延时执行——聚合形态与引擎同 JVM，同步链会把
     * 业务状态机跃迁压缩进单次审批请求（REQ-20261122-233425 试点状态链可观测面），异步解耦后
     * handler 异常不反噬下发（无引擎重试，失败仅告警），使用方按形态取舍）。
     */
    private boolean receiveAsync = false;

    /** 异步模式：受理后 handler 执行延时毫秒（0=立即；状态链可观测面按部署形态调整）。 */
    private long receiveInitialDelayMs = 0L;

    /** 异步模式：handler 完成后回调延时毫秒（0=立即——handler 结果落库对引擎推进可见窗口）。 */
    private long receiveCallbackDelayMs = 0L;

    public String getReceivePathPrefix() {
        return receivePathPrefix;
    }

    public void setReceivePathPrefix(String receivePathPrefix) {
        this.receivePathPrefix = receivePathPrefix;
    }

    public String getCallbackBaseUrl() {
        return callbackBaseUrl;
    }

    public void setCallbackBaseUrl(String callbackBaseUrl) {
        this.callbackBaseUrl = callbackBaseUrl;
    }

    public String getWorkflowServiceName() {
        return workflowServiceName;
    }

    public void setWorkflowServiceName(String workflowServiceName) {
        this.workflowServiceName = workflowServiceName;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public boolean isReceiveAsync() {
        return receiveAsync;
    }

    public void setReceiveAsync(boolean receiveAsync) {
        this.receiveAsync = receiveAsync;
    }

    public long getReceiveInitialDelayMs() {
        return receiveInitialDelayMs;
    }

    public void setReceiveInitialDelayMs(long receiveInitialDelayMs) {
        this.receiveInitialDelayMs = receiveInitialDelayMs;
    }

    public long getReceiveCallbackDelayMs() {
        return receiveCallbackDelayMs;
    }

    public void setReceiveCallbackDelayMs(long receiveCallbackDelayMs) {
        this.receiveCallbackDelayMs = receiveCallbackDelayMs;
    }
}
