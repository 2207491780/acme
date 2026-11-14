package com.acme.platform.infra.interfaces;

import com.acme.common.core.BizException;
import com.acme.platform.infra.InfraError;
import com.acme.platform.infra.application.ExcelDemoUseCase;
import com.acme.platform.infra.application.ImportReport;
import com.acme.platform.infra.security.RequirePermission;
import com.acme.platform.system.api.SystemPermissions;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Excel 通用范式 demo 接口（REQ-20261114-071607 §API；导入导出不接示范域——仅通用能力封装，
 * DemoRow 不落业务表）：模板下载 / 确定性导出 / validate-only 导入校验（framework-excel
 * StreamExcelReader/Writer 复用）。出参经 {@link ExcelDemoUseCase} 确定性归一字节直写响应流
 * （二进制出参豁免 Result 信封，Modules.md 3.1.1）；AC-9「同输入同输出」在 HTTP 出参同样成立。
 */
@RestController
@RequestMapping("/admin/v1/infra/excel")
public class ExcelDemoController {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ExcelDemoUseCase excelDemoUseCase;

    public ExcelDemoController(ExcelDemoUseCase excelDemoUseCase) {
        this.excelDemoUseCase = excelDemoUseCase;
    }

    /** 模板下载（表头 + 一行示例，AC-8；单点权限 infra:excel:demo，裁决⑦）。 */
    @GetMapping("/demo-template")
    @RequirePermission(SystemPermissions.INFRA_EXCEL_DEMO)
    public void demoTemplate(HttpServletResponse response) throws IOException {
        writeWorkbook(response, excelDemoUseCase.templateBytes(), "demo-template.xlsx");
    }

    /** 确定性导出（20 行 DemoRow，同输入同输出，AC-9；单点权限 infra:excel:demo）。 */
    @GetMapping("/demo-export")
    @RequirePermission(SystemPermissions.INFRA_EXCEL_DEMO)
    public void demoExport(HttpServletResponse response) throws IOException {
        writeWorkbook(response, excelDemoUseCase.exportBytes(), "demo-export.xlsx");
    }

    /** validate-only 导入校验（行级校验不落库，AC-10/AC-11；文件非法 → EXCEL_PARSE_FAILED；单点权限）。 */
    @PostMapping("/demo-import")
    @RequirePermission(SystemPermissions.INFRA_EXCEL_DEMO)
    public ImportReport demoImport(@RequestParam("file") MultipartFile file) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException ex) {
            throw new BizException(InfraError.EXCEL_PARSE_FAILED, null, ex);
        }
        return excelDemoUseCase.importReport(content);
    }

    /** xlsx 直写响应流（demo 文件名为 ASCII 常量，Content-Disposition 原名直用）。 */
    private static void writeWorkbook(HttpServletResponse response, byte[] xlsx, String fileName)
            throws IOException {
        response.setContentType(XLSX_CONTENT_TYPE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
        response.getOutputStream().write(xlsx);
    }
}
