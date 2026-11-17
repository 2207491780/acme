package com.acme.framework.excel.config;

import com.acme.framework.excel.internal.ExcelHttpExporter;
import com.acme.framework.excel.support.ExcelReader;
import com.acme.framework.excel.support.ExcelWriter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块开关与配置缺省测试（FR-024/AC-024、BR-003/004、Q-D004 默认 COLLECT）。
 */
class ExcelSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ExcelAutoConfiguration.class));

    @Test
    void disabledByOneLineConfigAssemblesNothing() {
        runner.withPropertyValues("acme.excel.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ExcelWriter.class);
                    assertThat(context).doesNotHaveBean(ExcelReader.class);
                    assertThat(context).doesNotHaveBean(ExcelHttpExporter.class);
                    assertThat(context).doesNotHaveBean(ExcelProperties.class);
                });
    }

    @Test
    void zeroConfigStartsWithDefaults() {
        runner.run(context -> {
            ExcelProperties properties = context.getBean(ExcelProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getImportErrorPolicy()).isEqualTo(ExcelProperties.ImportErrorPolicy.COLLECT);
            // REQ-20261117-061017 确认④：同步导出上限 10000 行（缺省值随契约调整）
            assertThat(properties.getMaxRows()).isEqualTo(10000);
            assertThat(properties.getReadBatchSize()).isEqualTo(500);
        });
    }

    @Test
    void customConfigBinds() {
        runner.withPropertyValues(
                        "acme.excel.import-error-policy=FAIL_FAST",
                        "acme.excel.max-rows=10",
                        "acme.excel.read-batch-size=5")
                .run(context -> {
                    ExcelProperties properties = context.getBean(ExcelProperties.class);
                    assertThat(properties.getImportErrorPolicy()).isEqualTo(ExcelProperties.ImportErrorPolicy.FAIL_FAST);
                    assertThat(properties.getMaxRows()).isEqualTo(10);
                    assertThat(properties.getReadBatchSize()).isEqualTo(5);
                });
    }
}
