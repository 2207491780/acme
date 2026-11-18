import { useCallback, useEffect, useRef, useState } from 'react';
import { downloadFile, transferErrorMessage } from './file-transfer';
import { uploadFormData } from './api/infra';
import type { ImportReport } from './api/infra';

/**
 * Excel 导出/导入 headless hooks（REQ-20261114-120101 前端收敛一期）。
 * Excel 解析在后端：前端只负责传文件（multipart）与展示 ImportReport（errors: row/field/message）。
 * 端点由调用方传入（demo 范式 /admin/v1/infra/excel/demo-*；业务端点接入时按同范式传 URL）。
 */

/** 卸载守护：在途请求返回后不再写已卸载组件的状态。 */
function useUnmountGuard(): { current: boolean } {
  const aliveRef = useRef(true);
  useEffect(() => {
    aliveRef.current = true;
    return () => {
      aliveRef.current = false;
    };
  }, []);
  return aliveRef;
}

export interface UseExcelExportResult {
  /** 是否有导出在途（按钮 loading）。 */
  exporting: boolean;
  /** 最近一次失败文案（null = 无错误）。 */
  error: string | null;
  /** 触发下载：GET url → blob → 按 Content-Disposition 原名保存（无头由 fallbackName / URL 末段兜底）。 */
  exportFile: (url: string, fallbackName?: string) => Promise<boolean>;
}

/** Excel 导出（headless）：下载在途态 + 失败转译；成功返回 true。 */
export function useExcelExport(): UseExcelExportResult {
  const alive = useUnmountGuard();
  const [exporting, setExporting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const exportFile = useCallback(async (url: string, fallbackName?: string): Promise<boolean> => {
    setExporting(true);
    setError(null);
    try {
      await downloadFile(url, fallbackName ?? url.split('/').filter(Boolean).pop() ?? 'export.xlsx');
      return true;
    } catch (ex: unknown) {
      if (alive.current) {
        setError(transferErrorMessage(ex, '导出失败，请稍后重试'));
      }
      return false;
    } finally {
      if (alive.current) {
        setExporting(false);
      }
    }
  }, []);

  return { exporting, error, exportFile };
}

export interface UseExcelImportResult {
  /** 是否有导入在途。 */
  importing: boolean;
  /** 最近一次成功导入的报告（errors: row/field/message；null = 无报告）。 */
  report: ImportReport | null;
  /** 最近一次失败文案（null = 无错误）。 */
  error: string | null;
  /** 触发导入：multipart 上传 xlsx → ImportReport（validate-only 不落库；url=file 目标端点）。 */
  importFile: (url: string, file: File) => Promise<ImportReport | null>;
  /** 清空报告（关闭报告弹窗时调用）。 */
  clearReport: () => void;
}

/** Excel 导入（headless）：上传在途态 + ImportReport 暂存 + EXCEL_PARSE_FAILED 等错误码转译。 */
export function useExcelImport(): UseExcelImportResult {
  const alive = useUnmountGuard();
  const [importing, setImporting] = useState(false);
  const [report, setReport] = useState<ImportReport | null>(null);
  const [error, setError] = useState<string | null>(null);

  const importFile = useCallback(async (url: string, file: File): Promise<ImportReport | null> => {
    setImporting(true);
    setError(null);
    try {
      const result = await uploadFormData<ImportReport>(url, file);
      if (alive.current) {
        setReport(result);
      }
      return result;
    } catch (ex: unknown) {
      if (alive.current) {
        setError(transferErrorMessage(ex, '导入失败，请稍后重试'));
      }
      return null;
    } finally {
      if (alive.current) {
        setImporting(false);
      }
    }
  }, []);

  const clearReport = useCallback(() => setReport(null), []);

  return { importing, report, error, importFile, clearReport };
}
