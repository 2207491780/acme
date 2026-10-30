package com.acme.framework.excel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Excel 导入导出配置（FR-004/005、8.1.3 全字段默认值；架构设计 15 章）。
 */
@ConfigurationProperties(prefix = "acme.excel")
public class ExcelProperties {

    /** 模块开关（3.5 可关闭性）。 */
    private boolean enabled = true;

    /** 行级错误处置（默认 COLLECT 汇总，Q-D004）。 */
    private ImportErrorPolicy importErrorPolicy = ImportErrorPolicy.COLLECT;

    /** 导入/导出最大行数。 */
    private long maxRows = 100000;

    /** 流式读入窗口（NFR-003 不整文件驻留）。 */
    private int readBatchSize = 500;

    /** 行级错误处置口径。 */
    public enum ImportErrorPolicy {
        /** 收集错误行汇总返回（默认，Q-D004）。 */
        COLLECT,
        /** 首错快速失败。 */
        FAIL_FAST
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public ImportErrorPolicy getImportErrorPolicy() {
        return importErrorPolicy;
    }

    public void setImportErrorPolicy(ImportErrorPolicy importErrorPolicy) {
        this.importErrorPolicy = importErrorPolicy;
    }

    public long getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(long maxRows) {
        this.maxRows = maxRows;
    }

    public int getReadBatchSize() {
        return readBatchSize;
    }

    public void setReadBatchSize(int readBatchSize) {
        this.readBatchSize = readBatchSize;
    }
}
