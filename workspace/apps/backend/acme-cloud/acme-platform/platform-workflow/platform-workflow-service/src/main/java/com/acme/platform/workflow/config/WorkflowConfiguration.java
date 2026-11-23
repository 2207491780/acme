package com.acme.platform.workflow.config;

import com.acme.common.core.Clock;
import com.acme.common.util.IdGenerator;
import com.acme.common.util.SnowflakeIdGenerator;
import com.acme.framework.flex.config.FlexProperties;
import com.acme.framework.stream.support.MessageSender;
import com.acme.platform.workflow.kernel.KafkaWorkflowEventPublisher;
import com.acme.platform.workflow.kernel.LoggingWorkflowEventPublisher;
import com.acme.platform.workflow.kernel.WorkflowEventPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * workflow 域基础装配：配置属性、内核时钟（common-core Clock 抽象——FR-019 统一时间获取口径，
 * 测试可注入可控时间源）、雪花 IdGenerator bean（12.3.1——容器级唯一生成器：AuditSupport 与
 * TimerScheduler 预分配共享同一实例，杜绝双生成器同 worker 序列碰撞）、事件发布 SPI 装配
 * （REQ-20261122-233425 确认④：Kafka 缺省（framework-stream 在场），Logging 兜底——单体/测试
 * 可注入自定义 WorkflowEventPublisher 覆盖）。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkflowProperties.class)
public class WorkflowConfiguration {

    /** 内核时钟（系统时钟缺省；命令内核/定时联动统一取用）。 */
    @Bean
    public Clock workflowClock() {
        return Clock.system();
    }

    /** 容器级雪花生成器（AuditSupport getBeanIfAvailable 优先取用——与定时器预分配同源）。 */
    @Bean
    public IdGenerator workflowIdGenerator(FlexProperties flexProperties) {
        FlexProperties.Id id = flexProperties.getId();
        if (id.getWorkerId() == null) {
            throw new IllegalStateException("未配置 acme.flex.id.worker-id（12.3.1：禁静默 0/0 回退）");
        }
        return new SnowflakeIdGenerator(id.getWorkerId(), id.getDatacenterId());
    }

    /**
     * 事件发布 SPI 装配（确认④：Kafka 缺省——framework-stream MessageSender bean 在场时；Logging
     * 兜底——acme.stream.enabled=false/无 MQ 形态）。判定在 bean 创建期经 ObjectProvider 解析
     * （@ConditionalOnBean 在组件扫描配置类对自动配置 bean 不可靠——注册序早于自动配置，实测
     * 会误判缺席；创建期自动配置已完成装配，判定可靠）。测试可 @MockBean 按类型覆盖。
     */
    @Bean
    public WorkflowEventPublisher workflowEventPublisher(ObjectProvider<MessageSender> messageSenders) {
        MessageSender sender = messageSenders.getIfAvailable();
        if (sender != null) {
            return new KafkaWorkflowEventPublisher(sender);
        }
        return new LoggingWorkflowEventPublisher();
    }
}
