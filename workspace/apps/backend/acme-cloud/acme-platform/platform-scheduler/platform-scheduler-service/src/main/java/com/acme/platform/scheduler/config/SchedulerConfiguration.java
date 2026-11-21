package com.acme.platform.scheduler.config;

import com.acme.common.core.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 调度域基础装配：配置属性、内核时钟（common-core Clock 抽象——FR-019 统一时间获取口径，
 * 测试可注入可控时间源，确认⑦内核语义测试口径）。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SchedulerProperties.class)
public class SchedulerConfiguration {

    /** 内核时钟（系统时钟缺省；内核 tick/巡检/下发统一取用）。 */
    @Bean
    public Clock schedulerClock() {
        return Clock.system();
    }
}
