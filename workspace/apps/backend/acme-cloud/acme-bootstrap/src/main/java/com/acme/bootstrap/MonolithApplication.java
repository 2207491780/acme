package com.acme.bootstrap;

import com.acme.platform.auth.PlatformAuthApplication;
import com.acme.platform.auth.config.AuthTokenProperties;
import com.acme.platform.auth.config.LoginGuardProperties;
import com.acme.platform.auth.config.LoginLogRetentionProperties;
import com.acme.platform.infra.PlatformInfraApplication;
import com.acme.platform.infra.config.AuditRetentionProperties;
import com.acme.platform.infra.config.ExceptionLogRetentionProperties;
import com.acme.platform.scheduler.PlatformSchedulerApplication;
import com.acme.platform.system.PlatformSystemApplication;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * 单体启动入口（REQ-20261118-002130 确认②③：三服务聚合为单 JVM 形态，可选打包路径——
 * 本地联调 / 小部署；分布式为默认形态，能力差异见 BACKEND.md「双形态」）。
 *
 * <p>装配口径：{@code scanBasePackages} 扫单体组件面（com.acme.bootstrap）与各服务组件面
 * （com.acme.platform）；各 Application 类作为普通配置类导入（其 @EnableFeignClients 显式
 * clients 数组在单体由「Feign 惰性 + @Primary 本地适配器 + 工厂 bean primary 降级」化解——
 * {@code spring.cloud.openfeign.lazy-attributes-resolution=true} 下 Feign 工厂 bean 惰性注册、
 * 永不实例化，跨域调用全部短路至本地适配器，见 {@code local} 包各类与
 * {@link MonolithContextDefinitionCustomizer}）。REQ-20261121-071002 确认⑥：
 * platform-scheduler 入聚合（tick 循环在单体形态随 Spring 启动，ShedLock 同款；
 * 下发线程池/RestClient bean 装配但单体无执行器注册时零下发行为）。</p>
 *
 * <p>{@code @MapperScan("com.acme.platform")}：mybatis-flex starter 的自动 mapper 注册按
 * AutoConfigurationPackages（主应用包 com.acme.bootstrap）扫描，单体主包不含 mapper 接口——
 * 显式扫描各服务 mapper 面（@MapperScan 在场使 starter 自动注册器退避，服务单跑语义不变）。</p>
 *
 * <p>中间件合并（确认③）：单一 DataSource（search_path 覆盖四 schema，{@code config} 包）、
 * 各手动 Flyway 实例（spring.flyway.enabled=false 承担 Boot 自动配置关闭）、Redis 一份、
 * Nacos 软依赖（无 config.import、discovery.enabled=false）、stream 关闭 + 本地审计 sink
 * （{@code audit} 包，AC-5 直落 acme_op_audit 无 Kafka）。</p>
 *
 * <p>边缘认证（确认④）：无网关形态由 {@code edge.BootstrapAuthFilter}（HIGHEST+150，先于
 * InternalTokenAuthFilter 的 +200）承接——公开路径豁免 → 用户令牌验签 → 撤销检查（FAIL_OPEN）
 * → JwsInternalTokenIssuer 签内部令牌注入 X-Acme-Token；网关特有能力（限流/XSS 清洗/灰度/
 * 伪造头剥离）单体不携带（BACKEND.md 能力差异表）。</p>
 *
 * <p>跨服务同名组件（AuthorizationGateway / RequirePermissionInterceptor /
 * LoginLogRetentionCleanupUseCase，auth·system·infra 各有一对）在单体单上下文扫描下重名——
 * 各自显式 bean 名消歧（@Component/@Service 命名消歧，仅名称注解、无行为变化，分布式不受影响）。</p>
 */
@SpringBootApplication(scanBasePackages = {"com.acme.bootstrap", "com.acme.platform"})
@MapperScan(basePackages = "com.acme.platform", annotationClass = Mapper.class)
@Import({PlatformAuthApplication.class, PlatformSystemApplication.class, PlatformInfraApplication.class,
        PlatformSchedulerApplication.class})
@EnableConfigurationProperties({AuthTokenProperties.class, LoginGuardProperties.class,
        LoginLogRetentionProperties.class, ExceptionLogRetentionProperties.class,
        AuditRetentionProperties.class})
public class MonolithApplication {

    public static void main(String[] args) {
        SpringApplication.run(MonolithApplication.class, args);
    }

    /**
     * 单体上下文定义面调整（REQ-20261118-002130 确认②③实地装配）：
     * <ol>
     *   <li>Feign 工厂 bean primary 降级——{@code @FeignClient} 的 primary 属性缺省 true，
     *       FeignClientsRegistrar 把每个 Feign 工厂 bean 定义注册为 primary，与本地适配器的
     *       {@code @Primary} 冲突（more than one 'primary' bean）：降级后跨域注入点由本地适配器
     *       胜出，Feign 定义保持惰性永不实例化；</li>
     *   <li>审计 MQ 消费函数定义移除——{@code auditEventIn}（platform-infra 的 SCSt 消费函数）
     *       在单体无 MQ 回环角色（本地 sink 直调落库），且其存在会使 SCSt 在空
     *       function.definition 下自动绑定并尝试 Kafka 装配——移除后 stream 出站/入站面全关。</li>
     * </ol>
     * 分布式不受影响（该处理器仅存在于单体上下文）。
     */
    @Bean
    static MonolithContextDefinitionCustomizer monolithContextDefinitionCustomizer() {
        return new MonolithContextDefinitionCustomizer();
    }

    /** 单体上下文定义调整处理器（注册时机在 FeignClientsRegistrar 之后）。 */
    static class MonolithContextDefinitionCustomizer implements BeanDefinitionRegistryPostProcessor {

        /** 审计 MQ 消费函数 bean 名（platform-infra AuditEventConsumer 的 @Bean 方法名）。 */
        private static final String AUDIT_EVENT_IN_FUNCTION = "auditEventIn";

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
            if (registry.containsBeanDefinition(AUDIT_EVENT_IN_FUNCTION)) {
                registry.removeBeanDefinition(AUDIT_EVENT_IN_FUNCTION);
            }
            for (String name : registry.getBeanDefinitionNames()) {
                BeanDefinition definition = registry.getBeanDefinition(name);
                // 惰性注册路径（lazy-attributes-resolution）的工厂 bean 定义以 instance supplier 构建
                // （beanClassName 为 null），Registrar 以该属性标记 Feign 定义——按属性识别，双路径兼容
                if (definition.hasAttribute("feignClientsRegistrarFactoryBean") && definition.isPrimary()) {
                    definition.setPrimary(false);
                }
            }
        }

        @Override
        public void postProcessBeanFactory(
                org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory) {
            // 仅需注册期处理（定义已在 FeignClientsRegistrar 生成），工厂期无事可做
        }
    }
}
