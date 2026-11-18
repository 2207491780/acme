package com.acme.framework.jackson;

import com.acme.framework.jackson.config.JacksonAutoConfiguration;
import com.acme.framework.jackson.support.AcmeJacksonComponent;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @AcmeJacksonComponent} 扫描生效测试（FR-008、AC-008；DQ-007 三类组件）。
 */
class JacksonComponentScanTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class,
                    JacksonAutoConfiguration.class))
            .withUserConfiguration(ComponentHolder.class);

    /** ValueSerializer 组件注册生效。 */
    @Test
    void valueSerializerComponentIsApplied() {
        runner.run(context -> {
            JsonMapper mapper = context.getBean(JsonMapper.class);
            assertThat(mapper.writeValueAsString(new Foo())).isEqualTo("\"foo-custom\"");
        });
    }

    /** ValueDeserializer 组件注册生效。 */
    @Test
    void valueDeserializerComponentIsApplied() {
        runner.run(context -> {
            JsonMapper mapper = context.getBean(JsonMapper.class);
            assertThat(mapper.readValue("\"bar-custom\"", Bar.class)).isNotNull();
        });
    }

    /** JacksonModule 组件注册生效。 */
    @Test
    void jacksonModuleComponentIsApplied() {
        runner.run(context -> {
            JsonMapper mapper = context.getBean(JsonMapper.class);
            assertThat(mapper.writeValueAsString(new Baz())).isEqualTo("\"baz-module\"");
        });
    }

    /** 与标准 Module 集合并存、互不覆盖（FR-008）：标准行为不被组件覆盖。 */
    @Test
    void standardBehaviorsNotOverriddenByComponents() {
        runner.run(context -> {
            JsonMapper mapper = context.getBean(JsonMapper.class);
            assertThat(mapper.writeValueAsString(new LongHolder(123L))).isEqualTo("{\"id\":\"123\"}");
        });
    }

    /** 标注组件非法（序列化器缺目标类型）随容器默认失败行为启动失败（FR-008 异常分支）。 */
    @Test
    void serializerWithoutTargetTypeFailsStartup() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class,
                        JacksonAutoConfiguration.class))
                .withUserConfiguration(IllegalSerializerHolder.class)
                .run(context -> assertThat(context).hasFailed());
    }

    /** CGLIB 代理下注解解析不失效（ultimateTargetClass 口径）。 */
    @Test
    void cglibProxiedComponentStillRegisters() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class,
                        JacksonAutoConfiguration.class))
                .withUserConfiguration(ProxiedComponentHolder.class)
                .run(context -> {
                    JsonMapper mapper = context.getBean(JsonMapper.class);
                    assertThat(mapper.writeValueAsString(new Foo())).isEqualTo("\"foo-custom\"");
                });
    }

    // ---------- 组件与夹具 ----------

    static class Foo {
    }

    static class Bar {
    }

    static class Baz {
    }

    static class LongHolder {
        public Long id;

        LongHolder() {
        }

        LongHolder(Long id) {
            this.id = id;
        }
    }

    @AcmeJacksonComponent(Foo.class)
    static class FooSerializer extends ValueSerializer<Foo> {
        @Override
        public void serialize(Foo value, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeString("foo-custom");
        }
    }

    @AcmeJacksonComponent(Bar.class)
    static class BarDeserializer extends ValueDeserializer<Bar> {
        @Override
        public Bar deserialize(JsonParser p, DeserializationContext ctxt) {
            if (!"bar-custom".equals(p.getString())) {
                throw DatabindException.from(p, "未知 Bar 输入");
            }
            return new Bar();
        }
    }

    @AcmeJacksonComponent
    static class BazModule extends SimpleModule {
        BazModule() {
            super("BazModule");
            addSerializer(Baz.class, new ValueSerializer<Baz>() {
                @Override
                public void serialize(Baz value, JsonGenerator gen, SerializationContext ctxt) {
                    gen.writeString("baz-module");
                }
            });
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ComponentHolder {
        @Bean
        FooSerializer fooSerializer() {
            return new FooSerializer();
        }

        @Bean
        BarDeserializer barDeserializer() {
            return new BarDeserializer();
        }

        @Bean
        BazModule bazModule() {
            return new BazModule();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class IllegalSerializerHolder {
        @Bean
        NoTargetSerializer noTargetSerializer() {
            return new NoTargetSerializer();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ProxiedComponentHolder {
        @Bean
        FooSerializer proxiedFooSerializer() {
            FooSerializer target = new FooSerializer();
            ProxyFactory proxyFactory = new ProxyFactory(target);
            proxyFactory.setProxyTargetClass(true);
            return (FooSerializer) proxyFactory.getProxy();
        }
    }

    @AcmeJacksonComponent
    static class NoTargetSerializer extends ValueSerializer<Foo> {
        @Override
        public void serialize(Foo value, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeString("x");
        }
    }
}
