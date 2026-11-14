import { useState } from 'react';
import { App, Button, Popconfirm, Space, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { DownloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
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
  const { t } = useTranslation();
  const { message } = App.useApp();
  const permissions = useMeStore((s) => s.me?.permissions) ?? [];

  const crud = useCrud<FileView>({
    fetchPage: ({ page, size, keyword, signal }) => infraApi.fileList(page, size, { keyword: keyword || undefined }, signal),
    permissions,
    pageSize: 10,
    errorMessage: t('files.errors.loadFailed'),
  });

  const [downloadingId, setDownloadingId] = useState<string | null>(null);
  const { exporting, exportFile } = useExcelExport();

  const download = async (record: FileView) => {
    setDownloadingId(record.id);
    try {
      // 后端以 Content-Disposition 下发原文件名；兜底名取列表里的 originalName
      await downloadFile(FILE_ENDPOINTS.content(record.id), record.originalName);
    } catch (ex: unknown) {
      message.error(transferErrorMessage(ex, t('common.errors.operationFailed')));
    } finally {
      setDownloadingId(null);
    }
  };

  const exportDemo = async () => {
    const ok = await exportFile(EXCEL_DEMO_ENDPOINTS.export, 'demo-export.xlsx');
    if (ok) {
      message.success(t('files.toast.downloaded'));
    }
  };

  const remove = async (record: FileView) => {
    try {
      await infraApi.fileDelete(record.id);
      message.success(t('files.toast.fileDeleted', { name: record.originalName }));
      crud.reload();
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : t('common.errors.deleteFailed'));
    }
  };

  const columns: ColumnsType<FileView> = [
    { title: t('files.columns.originalName'), dataIndex: 'originalName', key: 'originalName', ellipsis: true },
    { title: t('files.columns.size'), dataIndex: 'size', key: 'size', width: 110, render: (v: number) => formatFileSize(Number(v)) },
    { title: t('files.columns.contentType'), dataIndex: 'contentType', key: 'contentType', width: 150, render: (v: string) => <Text code style={{ fontSize: 11 }}>{v}</Text> },
    {
      title: t('files.columns.biz'), key: 'biz', width: 150,
      render: (_, record) => (record.bizType ? <Tag>{`${record.bizType}${record.bizId ? `:${record.bizId}` : ''}`}</Tag> : '—'),
    },
    { title: t('files.columns.createdAt'), dataIndex: 'createdAt', key: 'createdAt', width: 180, render: (v: string) => formatDate(v) },
    {
      title: t('files.columns.actions'), key: 'actions', width: 160,
      render: (_, record) => (
        <Space size={4}>
          {crud.can('infra:file:download') ? (
            <Button
              size="small"
              icon={<DownloadOutlined />}
              loading={downloadingId === record.id}
              onClick={() => void download(record)}
            >
              {t('files.actions.download')}
            </Button>
          ) : null}
          {crud.can('infra:file:delete') ? (
            <Popconfirm title={t('files.actions.deleteConfirm', { name: record.originalName })} onConfirm={() => void remove(record)}>
              <Button size="small" danger>{t('files.actions.delete')}</Button>
            </Popconfirm>
          ) : null}
        </Space>
      ),
    },
  ];

  return (
    <CrudTable<FileView>
      crud={crud}
      title={t('files.title')}
      columns={columns}
      searchPlaceholder={t('files.search.placeholder')}
      searchWidth={200}
      reloadAriaLabel={t('files.reload')}
      totalText={(total) => t('files.total', { total })}
      actions={
        <Space size={4} wrap>
          <UploadButton
            label={t('files.actions.upload')}
            onUploaded={() => crud.reload(1)}
          />
          {crud.can('infra:file:download') ? (
            <Button loading={exporting} onClick={() => void exportDemo()}>{t('files.actions.exportExcel')}</Button>
          ) : null}
          <ExcelImportButton
            url={EXCEL_DEMO_ENDPOINTS.import}
            templateUrl={EXCEL_DEMO_ENDPOINTS.template}
            label={t('files.actions.importExcel')}
          />
        </Space>
      }
    />
  );
}
