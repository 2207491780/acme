package com.acme.framework.job.executor;

import com.acme.framework.job.config.JobAutoConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261121-200724（JOB-2）AC-5 SDK 腿——执行器总开关关闭（单体纯本地模式）隔离测试：
 * {@code acme.job.executor.enabled=false} 时零注册零心跳（对调度中心零 HTTP 出站），
 * 本地 {@code @Scheduled} 全量照跑（bootstrap 冒烟口径的框架域等价面；bootstrap 装配腿见
 * acme-bootstrap 模块 REQ20261121200724MonolithExecutorDisabledSmokeTest）。
 *
 * <p>内嵌桩调度中心照 JOB-1 范本先例（JDK HttpServer + 全量请求记录）；
 * heartbeat-interval-ms=200 提高观测分辨率（零出站断言窗口 2s ≈ 10 个心跳周期）。</p>
 */
@SpringBootTest(classes = REQ20261121200724ExecutorDisabledIsolationTest.DisabledSdkTestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.application.name=acme-job2-sdk-it",
        "acme.job.executor.enabled=false",
        "acme.job.executor.heartbeat-interval-ms=200",
        "acme.security.token.hmac-secret=req-job2-sdk-it-secret-0123456789"
})
class REQ20261121200724ExecutorDisabledIsolationTest {

    private static final Pattern ANY_SCHEDULER_CALL =
            Pattern.compile("/internal/v1/(scheduler/executors|scheduler/executors/heartbeat|scheduler/executions/.*/result)");

    private static final LocalStub SCHEDULER = LocalStub.start();

    @DynamicPropertySource
    static void lbTarget(DynamicPropertyRegistry registry) {
        // 即使误注册也只会打到桩（出站可观测；静态实例表照 JOB-1 范本 A-2）
        registry.add("spring.cloud.discovery.client.simple.instances.platform-scheduler[0].uri",
                SCHEDULER::baseUri);
    }

    @Autowired
    private LocalProbe probe;

    @AfterEach
    void resetStub() {
        SCHEDULER.reset();
        probe.reset();
    }

    @Test
    void should_never_contact_scheduler_and_still_run_local_schedule_when_executor_disabled()
            throws Exception {
        // 本地 @Scheduled 兜底照跑（单体纯本地模式语义）
        assertThat(probe.fallbackTick.await(4, TimeUnit.SECONDS))
                .as("executor.enabled=false 时本地 @Scheduled 应照跑").isTrue();

        // 观察窗 2s（≈10 个心跳周期）：对调度中心零出站（注册/心跳/结果上报全无）
        long requestsBefore = SCHEDULER.requests().size();
        Thread.sleep(2_000);
        assertThat(SCHEDULER.requests().size() - requestsBefore)
                .as("executor.enabled=false 应零注册零心跳零上报，实际出站=%s",
                        SCHEDULER.requests())
                .isZero();
        assertThat(requestsTo(ANY_SCHEDULER_CALL))
                .as("全程不得有任何调度中心调用").isEmpty();
    }

    private static List<LocalStub.RecordedRequest> requestsTo(Pattern path) {
        return SCHEDULER.requests().stream()
                .filter(r -> path.matcher(r.path()).matches())
                .toList();
    }

    // ---------- 内嵌桩调度中心（JDK HttpServer，JOB-1 范本先例） ----------

    /** 桩调度中心：仅记录请求（本测试断言零请求，不区分场景）。 */
    static final class LocalStub {

        record RecordedRequest(String method, String path, String body) {
        }

        private final com.sun.net.httpserver.HttpServer server;
        private final List<RecordedRequest> requests = new java.util.concurrent.CopyOnWriteArrayList<>();

        private LocalStub(com.sun.net.httpserver.HttpServer server) {
            this.server = server;
        }

        static LocalStub start() {
            try {
                com.sun.net.httpserver.HttpServer server =
                        com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                LocalStub stub = new LocalStub(server);
                server.createContext("/", exchange -> {
                    byte[] requestBody = exchange.getRequestBody().readAllBytes();
                    stub.requests.add(new LocalStub.RecordedRequest(
                            exchange.getRequestMethod(),
                            exchange.getRequestURI().getPath(),
                            new String(requestBody, java.nio.charset.StandardCharsets.UTF_8)));
                    byte[] bytes = "{\"success\":true,\"code\":\"0\",\"data\":null}"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
                server.setExecutor(Executors.newCachedThreadPool());
                server.start();
                return stub;
            } catch (IOException ex) {
                throw new UncheckedIOException("内嵌桩调度中心启动失败", ex);
            }
        }

        String baseUri() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        List<RecordedRequest> requests() {
            return List.copyOf(requests);
        }

        void reset() {
            requests.clear();
        }
    }

    // ---------- 测试应用与探针 ----------

    /** SDK 关闭态宿主：全量自动装配（job auto-configuration 应零执行器 bean）。 */
    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableScheduling
    static class DisabledSdkTestApp {

        @Bean
        LocalProbe localProbe() {
            return new LocalProbe();
        }

        /** 数据源桩件（JobSwitchTest 先例）：防 AUTO 互斥模式误判 REDIS（test classpath 有
         *  spring-data-redis 无 lettuce 工厂）；executor.enabled=false 仍装配本地互斥面。 */
        @Bean
        javax.sql.DataSource dataSource() {
            return (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
                    DisabledSdkTestApp.class.getClassLoader(), new Class<?>[] {javax.sql.DataSource.class},
                    (p, method, args) -> {
                        if ("getConnection".equals(method.getName())) {
                            throw new java.sql.SQLException("stub");
                        }
                        if ("unwrap".equals(method.getName())) {
                            throw new java.sql.SQLException("stub");
                        }
                        if ("isWrapperFor".equals(method.getName())) {
                            return false;
                        }
                        return null;
                    });
        }
    }

    /** 本地任务探针：仅 @Scheduled 兜底任务（无 @JobHandler/@AcmeTask 干扰面）。 */
    static class LocalProbe {

        final CountDownLatch fallbackTick = new CountDownLatch(1);

        void reset() {
            // latch 已触发不可复用——本类单用例，无需重置语义
        }

        @Scheduled(fixedDelay = 200)
        public void localFallback() {
            fallbackTick.countDown();
        }
    }
}
