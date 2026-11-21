package com.acme.platform.workflow.config;

import com.acme.common.core.Clock;
import com.acme.common.util.IdGenerator;
import com.acme.common.util.SnowflakeIdGenerator;
import com.acme.framework.flex.config.FlexProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * workflow 域基础装配：配置属性、内核时钟（common-core Clock 抽象——FR-019 统一时间获取口径，
 * 测试可注入可控时间源）、雪花 IdGenerator bean（12.3.1——容器级唯一生成器：AuditSupport 与
 * TimerScheduler 预分配共享同一实例，杜绝双生成器同 worker 序列碰撞）。
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
}
