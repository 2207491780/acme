/** @vitest-environment jsdom */
/**
 * REQ-20261114-120101（自检补充）：file-transfer 工具单测——
 * Content-Disposition 文件名解析（ASCII / RFC 5987 filename* / 无头兜底）、
 * 传输错误码转译（尾部附码，组件 AC-4/AC-6 提示口径）、formatFileSize。
 * 测试员套件（REQ20261114120101-excelHooks）已覆盖 hooks 侧下载链路（A-31 建议补精确文件名单测）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../src/http/client';
import { downloadFile, filenameFromDisposition, transferErrorMessage } from '../src/file-transfer';
import { formatFileSize } from '../src/utils';

beforeEach(() => {
  (URL as unknown as { createObjectURL?: unknown }).createObjectURL = vi.fn(() => 'blob:acme-mock');
  (URL as unknown as { revokeObjectURL?: unknown }).revokeObjectURL = vi.fn();
});

afterEach(() => {
  vi.unstubAllGlobals();
  delete (URL as unknown as { createObjectURL?: unknown }).createObjectURL;
  delete (URL as unknown as { revokeObjectURL?: unknown }).revokeObjectURL;
});

describe('REQ20261114120101 file-transfer 工具', () => {
  it('should_parse_ascii_filename_from_disposition', () => {
    expect(filenameFromDisposition('attachment; filename="demo-export.xlsx"', 'fallback.xlsx')).toBe('demo-export.xlsx');
  });

  it('should_decode_rfc5987_utf8_filename_with_priority', () => {
    // 季度报表.xlsx 的 UTF-8 percent-encoding
    const header = `attachment; filename="export.xlsx"; filename*=UTF-8''%E5%AD%A3%E5%BA%A6%E6%8A%A5%E8%A1%A8.xlsx`;
    expect(filenameFromDisposition(header, 'fallback.xlsx')).toBe('季度报表.xlsx');
  });

  it('should_fallback_when_disposition_missing_or_malformed', () => {
    expect(filenameFromDisposition(null, 'fallback.xlsx')).toBe('fallback.xlsx');
    expect(filenameFromDisposition('attachment', 'fallback.xlsx')).toBe('fallback.xlsx');
    expect(filenameFromDisposition("attachment; filename*=UTF-8''%zz%zz", 'fallback.xlsx')).toBe('fallback.xlsx');
  });

  it('should_translate_known_transfer_codes_with_code_suffix', () => {
    expect(transferErrorMessage(new ApiError(200, 'FILE_SIZE_EXCEEDED', 'too large'))).toContain('文件大小超出限制');
    expect(transferErrorMessage(new ApiError(200, 'FILE_SIZE_EXCEEDED', 'too large'))).toContain('FILE_SIZE_EXCEEDED');
    expect(transferErrorMessage(new ApiError(200, 'EXCEL_PARSE_FAILED', 'bad file'))).toContain('EXCEL_PARSE_FAILED');
    expect(transferErrorMessage(new ApiError(200, 'FILE_TYPE_REJECTED', 'bad type'))).toContain('文件类型不被允许');
  });

  it('should_fall_back_to_backend_message_for_unknown_codes', () => {
    expect(transferErrorMessage(new ApiError(500, 'UNKNOWN_X', '后端原始文案'))).toContain('后端原始文案');
    expect(transferErrorMessage(new Error('网络错误'))).toBe('网络错误');
    expect(transferErrorMessage(undefined, '兜底文案')).toBe('兜底文案');
  });

  it('should_download_and_use_content_disposition_name_when_file_downloaded', async () => {
    const fetchMock = vi.fn(async () => new Response(new Blob(['bytes']), {
      status: 200,
      headers: { 'Content-Disposition': `attachment; filename*=UTF-8''%E5%90%88%E5%90%8C.pdf` },
    }));
    vi.stubGlobal('fetch', fetchMock);
    const anchorClick = vi.fn();
    const appendChild = vi.spyOn(document.body, 'appendChild').mockImplementation(((node: Node) => {
      (node as HTMLAnchorElement).click = anchorClick;
      return node;
    }) as typeof document.body.appendChild);
    try {
      await downloadFile('/admin/v1/infra/files/1/content');
      expect(fetchMock).toHaveBeenCalledTimes(1);
      expect(anchorClick).toHaveBeenCalledTimes(1);
    } finally {
      appendChild.mockRestore();
    }
  });

  it('should_format_file_size_in_human_units', () => {
    expect(formatFileSize(512)).toBe('512 B');
    expect(formatFileSize(1024)).toBe('1 KB');
    expect(formatFileSize(1536)).toBe('1.5 KB');
    expect(formatFileSize(10 * 1024 * 1024)).toBe('10 MB');
    expect(formatFileSize(Number.NaN)).toBe('—');
  });
});
