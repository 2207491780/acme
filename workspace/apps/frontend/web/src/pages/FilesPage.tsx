import { useState } from 'react';
import { App, Button, Popconfirm, Space, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { DownloadOutlined } from '@ant-design/icons';
import {
  EXCEL_DEMO_ENDPOINTS,
  FILE_ENDPOINTS,
  ApiError,
  downloadFile,
  formatDate,
  formatFileSize,
  infraApi,
  transferErrorMessage,
  useCrud,
  useExcelExport,
  useMeStore,
  type FileView,
} from '@acme/core';
import { CrudTable } from '../components/CrudTable';
import { UploadButton } from '../components/UploadButton';
import { ExcelImportButton } from '../components/ExcelImportButton';

const { Text } = Typography;

/** 文件管理（REQ-20261114-120101，权限点 infra:file:list）：
 *  分页/搜索/上传/下载（Content-Disposition 原名）/删除（软删幂等）；
 *  Excel demo 导出/导入（后端解析范式）以工具栏动作挂载，业务端点接入时按同范式传 URL。 */
export function FilesPage() {
  const { message } = App.useApp();
  const permissions = useMeStore((s) => s.me?.permissions ?? []);

  const crud = useCrud<FileView>({
    fetchPage: ({ page, size, keyword }) => infraApi.fileList(page, size, { keyword: keyword || undefined }),
    permissions,
    pageSize: 10,
    errorMessage: '文件清单加载失败',
  });

  const [downloadingId, setDownloadingId] = useState<string | null>(null);
  const { exporting, exportFile } = useExcelExport();

  const download = async (record: FileView) => {
    setDownloadingId(record.id);
    try {
      // 后端以 Content-Disposition 下发原文件名；兜底名取列表里的 originalName
      await downloadFile(FILE_ENDPOINTS.content(record.id), record.originalName);
    } catch (ex: unknown) {
      message.error(transferErrorMessage(ex, '下载失败，请稍后重试'));
    } finally {
      setDownloadingId(null);
    }
  };

  const exportDemo = async () => {
    const ok = await exportFile(EXCEL_DEMO_ENDPOINTS.export, 'demo-export.xlsx');
    if (ok) {
      message.success('Excel 示例已导出');
    }
  };

  const remove = async (record: FileView) => {
    try {
      await infraApi.fileDelete(record.id);
      message.success(`文件 ${record.originalName} 已删除`);
      crud.reload();
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : '删除失败，请稍后重试');
    }
  };

  const columns: ColumnsType<FileView> = [
    { title: '文件名', dataIndex: 'originalName', key: 'originalName', ellipsis: true },
    { title: '体积', dataIndex: 'size', key: 'size', width: 110, render: (v: number) => formatFileSize(Number(v)) },
    { title: 'Content-Type', dataIndex: 'contentType', key: 'contentType', width: 150, render: (v: string) => <Text code style={{ fontSize: 11 }}>{v}</Text> },
    {
      title: '业务', key: 'biz', width: 150,
      render: (_, record) => (record.bizType ? <Tag>{`${record.bizType}${record.bizId ? `:${record.bizId}` : ''}`}</Tag> : '—'),
    },
    { title: '时间', dataIndex: 'createdAt', key: 'createdAt', width: 180, render: (v: string) => formatDate(v) },
    {
      title: '操作', key: 'actions', width: 160,
      render: (_, record) => (
        <Space size={4}>
          {crud.can('infra:file:download') ? (
            <Button
              size="small"
              icon={<DownloadOutlined />}
              loading={downloadingId === record.id}
              onClick={() => void download(record)}
            >
              下载
            </Button>
          ) : null}
          {crud.can('infra:file:delete') ? (
            <Popconfirm title={`确认删除文件 ${record.originalName}？`} onConfirm={() => void remove(record)}>
              <Button size="small" danger>删除</Button>
            </Popconfirm>
          ) : null}
        </Space>
      ),
    },
  ];

  return (
    <CrudTable<FileView>
      crud={crud}
      title="文件管理"
      columns={columns}
      searchPlaceholder="搜索文件名"
      searchWidth={200}
      reloadAriaLabel="刷新文件清单"
      totalText={(t) => `共 ${t} 个文件`}
      actions={
        <Space size={4} wrap>
          <UploadButton
            label="上传文件"
            onUploaded={() => crud.reload(1)}
          />
          {crud.can('infra:file:download') ? (
            <Button loading={exporting} onClick={() => void exportDemo()}>导出 Excel 示例</Button>
          ) : null}
          <ExcelImportButton
            url={EXCEL_DEMO_ENDPOINTS.import}
            templateUrl={EXCEL_DEMO_ENDPOINTS.template}
            label="导入 Excel 示例"
          />
        </Space>
      }
    />
  );
}
