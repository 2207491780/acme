package com.acme.framework.excel.config;

import com.acme.framework.core.config.AcmeModuleAutoConfiguration;
import com.acme.framework.core.config.ConditionalOnAcmeModule;
import com.acme.framework.excel.internal.ExcelHttpExporter;
import com.acme.framework.excel.support.ExcelReader;
import com.acme.framework.excel.support.ExcelWriter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * framework-excel 总装（FR-024 开关装配），经 AutoConfiguration.imports 注册（Modules.md 3.5）；
 * 承载库 FastExcel（Q-D001）；业务字段映射归业务侧（BR-010）。
 */
@AutoConfiguration
@ConditionalOnAcmeModule("excel")
@EnableConfigurationProperties(ExcelProperties.class)
public class ExcelAutoConfiguration extends AcmeModuleAutoConfiguration {

    /** 导出写门面（FR-004）。 */
    @Bean
    public ExcelWriter excelWriter(ExcelProperties properties) {
        return new ExcelWriter(properties);
    }

    /** 导入读门面（FR-005）。 */
    @Bean
    public ExcelReader excelReader(ExcelProperties properties) {
        return new ExcelReader(properties);
    }

    /** HTTP 导出（FR-004）。 */
    @Bean
    public ExcelHttpExporter excelHttpExporter(ExcelWriter excelWriter) {
        return new ExcelHttpExporter(excelWriter);
    }
}
