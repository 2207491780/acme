package com.acme.framework.excel.internal;

import com.acme.framework.excel.support.ExcelWriter;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * HTTP 导出（FR-004：Content-Disposition + 响应流；Servlet 语境随 framework-web）。
 * 写出中断（响应已提交）→ ERROR 日志 + 连接中断，不静默当成功（架构设计 6.2）。
 */
public class ExcelHttpExporter {

    private final ExcelWriter writer;

    public ExcelHttpExporter(ExcelWriter writer) {
        this.writer = writer;
    }

    /** 注解映射导出到 HTTP 响应。 */
    public void export(HttpServletResponse response, String fileName, List<?> records, Class<?> type) {
        prepare(response, fileName);
        try {
            writer.write(records, type, response.getOutputStream());
        } catch (IOException ex) {
            throw new UncheckedIOException("Excel 导出响应流中断", ex);
        }
    }

    /** 回调映射导出到 HTTP 响应。 */
    public <T> void export(HttpServletResponse response, String fileName, List<T> records,
                           com.acme.framework.excel.support.ExcelMapper<T> mapper) {
        prepare(response, fileName);
        try {
            writer.write(records, mapper, response.getOutputStream());
        } catch (IOException ex) {
            throw new UncheckedIOException("Excel 导出响应流中断", ex);
        }
    }

    private static void prepare(HttpServletResponse response, String fileName) {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        String encoded = URLEncoder.encode(fileName == null ? "export.xlsx" : fileName, StandardCharsets.UTF_8)
                .replace("+", "%20");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
    }
}
