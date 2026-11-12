package com.acme.framework.excel.internal;

import cn.idev.excel.FastExcel;
import cn.idev.excel.context.AnalysisContext;
import cn.idev.excel.read.listener.ReadListener;
import com.acme.common.core.BizException;
import com.acme.framework.excel.config.ExcelProperties;
import com.acme.framework.excel.support.ExcelError;
import com.acme.framework.excel.support.ExcelImportResult;
import com.acme.framework.excel.support.ExcelRowMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 流式读入（FR-005/AC-005、NFR-003：窗口 {@code read-batch-size}，不整文件驻留内存；
 * 承载库 FastExcel，Q-D001）。文件损坏/格式不符显式失败（SYS-12-0002）。
 */
public final class StreamExcelReader {

    private StreamExcelReader() {
    }

    /**
     * 按回调解析行（行级错误按 {@code import-error-policy} 处置）。
     */
    @SuppressWarnings("unchecked")
    public static <T> ExcelImportResult<T> read(InputStream in, ExcelRowMapper<T> rowMapper,
                                                ExcelProperties properties) {
        List<Object> records = new ArrayList<>();
        List<ExcelImportResult.ExcelRowError> rowErrors = new ArrayList<>();
        ReadListener<Map<Integer, String>> listener = new WindowedListener(records, rowErrors, rowMapper, properties);
        try {
            FastExcel.read(in).excelType(cn.idev.excel.support.ExcelTypeEnum.XLSX)
                    .registerReadListener(listener).sheet().doRead();
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BizException(ExcelError.FILE_INVALID, null, ex);
        }
        return new ExcelImportResult<>((List<T>) records, rowErrors);
    }

    /**
     * 按注解映射解析行（类型映射由 {@link SheetMapping} 提供）。
     */
    public static <T> ExcelImportResult<T> read(InputStream in, Class<T> type,
                                                ExcelProperties properties) {
        SheetMapping.Mapping mapping = SheetMapping.resolve(type);
        return read(in, (cells, rowNum) -> {
            try {
                T record = type.getDeclaredConstructor().newInstance();
                for (int i = 0; i < mapping.columns().size(); i++) {
                    mapping.columns().get(i).write(record, cells.get(i));
                }
                return record;
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("记录构造失败", ex);
            }
        }, properties);
    }

    /** 窗口式监听（read-batch-size 批量映射，NFR-003）。 */
    private static final class WindowedListener implements ReadListener<Map<Integer, String>> {

        private final List<TypedRow> window = new ArrayList<>();
        private final List<Object> records;
        private final List<ExcelImportResult.ExcelRowError> rowErrors;
        private final ExcelRowMapper<?> rowMapper;
        private final ExcelProperties properties;
        private int rowNum;

        private record TypedRow(int rowNum, Map<Integer, String> cells) {
        }

        private WindowedListener(List<Object> records, List<ExcelImportResult.ExcelRowError> rowErrors,
                                 ExcelRowMapper<?> rowMapper, ExcelProperties properties) {
            this.records = records;
            this.rowErrors = rowErrors;
            this.rowMapper = rowMapper;
            this.properties = properties;
        }

        @Override
        public void invoke(Map<Integer, String> cells, AnalysisContext context) {
            rowNum++;
            window.add(new TypedRow(rowNum, cells));
            if (window.size() >= Math.max(properties.getReadBatchSize(), 1)) {
                drain();
            }
        }

        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {
            drain();
        }

        @Override
        public void onException(Exception ex, AnalysisContext context) {
            throw new BizException(ExcelError.FILE_INVALID, null, ex);
        }

        @SuppressWarnings("unchecked")
        private void drain() {
            for (TypedRow row : window) {
                process(row.rowNum(), row.cells());
            }
            window.clear();
        }

        @SuppressWarnings("unchecked")
        private void process(int current, Map<Integer, String> cells) {
            if (records.size() + rowErrors.size() >= properties.getMaxRows()) {
                throw new BizException(ExcelError.ROW_LIMIT_EXCEEDED);
            }
            try {
                Object record = ((ExcelRowMapper<Object>) rowMapper).map(cells, current);
                if (record != null) {
                    records.add(record);
                }
            } catch (BizException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                if (properties.getImportErrorPolicy() == ExcelProperties.ImportErrorPolicy.FAIL_FAST) {
                    throw new BizException(ExcelError.ROW_INVALID, "行 " + current + " 非法", ex);
                }
                rowErrors.add(new ExcelImportResult.ExcelRowError(current, ex.getMessage()));
            }
        }
    }
}
