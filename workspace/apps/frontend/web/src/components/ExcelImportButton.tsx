import { useEffect, useRef, useState, type ChangeEvent, type ReactNode } from 'react';
import { App as AntApp, Button, Modal, Space, Statistic, Table, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { CloudUploadOutlined, DownloadOutlined } from '@ant-design/icons';
import { downloadFile, useExcelImport, type ImportReportError } from '@acme/core';

const { Text } = Typography;

export interface ExcelImportButtonProps {
  /** 导入端点（demo 范式 /admin/v1/infra/excel/demo-import；业务端点按同范式传 URL）。 */
  url: string;
  /** 可选模板下载端点（提供时渲染「下载模板」入口）。 */
  templateUrl?: string;
  label?: ReactNode;
  /** 文件选择过滤（缺省仅 xlsx）。 */
  accept?: string;
  /** 导入成功回调（页面据此刷新数据）。 */
  onImported?: (report: { total: number; valid: number; errors: ImportReportError[] }) => void;
}

/**
 * 通用 Excel 导入按钮（AntD5 展示层，REQ-20261114-120101）：
 * multipart 上传（Excel 解析在后端，前端只传文件）；成功后弹窗展示 ImportReport
 * （总数/有效/错误统计 + errors 行级明细 row/field/message）；EXCEL_PARSE_FAILED 统一转译提示。
 */
export function ExcelImportButton({
  url,
  templateUrl,
  label = '导入 Excel',
  accept = '.xlsx',
  onImported,
}: ExcelImportButtonProps) {
  const { message } = AntApp.useApp();
  const inputRef = useRef<HTMLInputElement>(null);
  const [templateDownloading, setTemplateDownloading] = useState(false);
  const { importing, report, error, importFile, clearReport } = useExcelImport();

  // hook 的失败文案经状态回传（避免闭包读到过期 error），统一在此转 toast
  useEffect(() => {
    if (error) {
      message.error(error);
    }
  }, [error, message]);

  const pick = () => {
    inputRef.current?.click();
  };

  const upload = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    // 允许重复选择同一文件
    event.target.value = '';
    if (!file) {
      return;
    }
    const result = await importFile(url, file);
    if (result) {
      message.success(`导入完成：共 ${result.total} 行，有效 ${result.valid} 行，错误 ${result.errors.length} 行`);
      onImported?.(result);
    }
  };

  const downloadTemplateFile = async () => {
    if (!templateUrl) {
      return;
    }
    setTemplateDownloading(true);
    try {
      await downloadFile(templateUrl, 'import-template.xlsx');
    } catch {
      // 失败文案由 hook.error 承载；此处仅在失败时提示
      message.error('模板下载失败，请稍后重试');
    } finally {
      setTemplateDownloading(false);
    }
  };

  const errorColumns: ColumnsType<ImportReportError> = [
    { title: '行号', dataIndex: 'row', key: 'row', width: 70 },
    { title: '字段', dataIndex: 'field', key: 'field', width: 140, render: (v: string | null) => v ?? '—' },
    { title: '原因', dataIndex: 'message', key: 'message' },
  ];

  return (
    <>
      <input
        ref={inputRef}
        type="file"
        accept={accept}
        style={{ display: 'none' }}
        onChange={(e) => void upload(e)}
        aria-hidden="true"
        tabIndex={-1}
      />
      <Space size={4}>
        <Button icon={<CloudUploadOutlined />} loading={importing} onClick={pick}>
          {label}
        </Button>
        {templateUrl ? (
          <Button icon={<DownloadOutlined />} loading={templateDownloading} onClick={() => void downloadTemplateFile()}>
            下载模板
          </Button>
        ) : null}
      </Space>
      <Modal
        open={report !== null}
        title="导入报告"
        width={640}
        onCancel={clearReport}
        footer={<Button type="primary" onClick={clearReport}>知道了</Button>}
        destroyOnClose
      >
        <Space size={32} wrap style={{ marginBottom: 16 }}>
          <Statistic title="总行数" value={report?.total ?? 0} />
          <Statistic title="有效" value={report?.valid ?? 0} valueStyle={{ color: 'var(--acid, #9db56a)' }} />
          <Statistic title="错误" value={report?.errors.length ?? 0} valueStyle={{ color: 'var(--red, #c47b7b)' }} />
        </Space>
        {report && report.errors.length > 0 ? (
          <Table<ImportReportError>
            rowKey={(r) => `${r.row}-${r.field ?? ''}-${r.message}`}
            columns={errorColumns}
            dataSource={report.errors}
            size="small"
            pagination={false}
            scroll={{ y: 320 }}
          />
        ) : (
          <Text type="secondary">全部行校验通过（validate-only 不落库）。</Text>
        )}
      </Modal>
    </>
  );
}
