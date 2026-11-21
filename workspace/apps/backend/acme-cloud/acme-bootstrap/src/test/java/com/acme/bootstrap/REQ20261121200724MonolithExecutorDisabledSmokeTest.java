package com.acme.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261121-200724（JOB-2）AC-5 bootstrap 装配腿——单体纯本地模式冒烟（源级轻量断言，
 * 手段如实上报；不做全上下文启动，全量启动冒烟面见 {@code MonolithContextSmokeTest}）：
 * <ul>
 *   <li>application-monolith.yml 显式 {@code acme.job.executor.enabled=false}
 *       （契约确认④：bootstrap 单体=纯本地模式，零注册零接单）。</li>
 *   <li>单体配置聚合 4 个存量保留期清理任务 cron 键（契约背景钉死；框架域兜底照跑的配置前提）。</li>
 * </ul>
 */
class REQ20261121200724MonolithExecutorDisabledSmokeTest {

    @Test
    void should_disable_executor_for_pure_local_mode_when_monolith_profile_active() throws IOException {
        String monolithYml = readSource("acme-bootstrap/src/main/resources/application-monolith.yml");

        // 执行器总开关显式关闭（契约确认④：单体 application-monolith.yml 显式 false=纯本地模式；
        // 正则容忍 executor: 与 enabled: 之间的注释行）
        assertThat(monolithYml)
                .as("单体配置应显式 acme.job.executor.enabled=false（JOB-2 变更清单）")
                .containsPattern("executor:\\s*\\n(\\s*#[^\\n]*\\n)*\\s+enabled:\\s*false");

        // 4 个存量任务 cron 键聚合（infra 缺省值；确认⑦种子 cron 同源）
        assertThat(monolithYml)
                .as("单体应聚合各任务 retention-cron 键（契约背景钉死）")
                .contains("0 30 3 * * *")
                .contains("0 40 3 * * *")
                .contains("0 40 4 * * *")
                .contains("0 0 3 * * *");
    }

    @Test
    void should_aggregate_infra_service_into_monolith_when_framework_job_delivered() throws IOException {
        // framework-job 经 platform-infra-service 传递聚合（任务宿主在 infra，单体随依赖聚合）
        String bootstrapPom = readSource("acme-bootstrap/pom.xml");
        assertThat(bootstrapPom)
                .as("bootstrap pom 应含 platform-infra-service（@JobHandler 任务宿主随聚合）")
                .contains("platform-infra-service");
    }

    // ---------- 源文件定位支撑（JOB-1 范本 AC-13/14 同款） ----------

    /** 从 user.dir 向上定位 acme-cloud 根（含 acme-gateway 目录）。 */
    private static Path acmeCloudRoot() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int i = 0; i < 10 && dir != null; i++, dir = dir.getParent()) {
            if (Files.exists(dir.resolve("acme-gateway/src/main/resources/application.yml"))) {
                return dir;
            }
        }
        throw new IllegalStateException(
                "无法定位 acme-cloud 根目录（user.dir=" + System.getProperty("user.dir") + "）");
    }

    private static String readSource(String relativePath) throws IOException {
        Path path = acmeCloudRoot().resolve(relativePath);
        assertThat(Files.exists(path))
                .as("源文件缺失（backend 未落盘或路径不符，疑点上报）：%s", path).isTrue();
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
