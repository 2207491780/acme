import { useState } from 'react';
import { App, Button, Descriptions, Drawer, Popconfirm, Space, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { DownloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  EXCEL_DEMO_ENDPOINTS,
  FILE_ENDPOINTS,
  ApiError,
  Auth,
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
 *  REQ-20261124-213008 范式 B：行操作「详情」→ Drawer + Descriptions 展示 FileView 全字段
 *  （GET /infra/files/{id}，infraApi.fileGet；FILE_NOT_FOUND 转译提示，不展示空壳抽屉）。
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
  const [detailFile, setDetailFile] = useState<FileView | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
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

  /** 打开详情抽屉（范式 B）：数据到位后才开抽屉——FILE_NOT_FOUND 等失败转译提示、不展示空壳。 */
  const openDetail = async (record: FileView) => {
    setDetailLoading(true);
    try {
      const view = await infraApi.fileGet(record.id);
      setDetailFile(view);
    } catch (ex: unknown) {
      if (ex instanceof ApiError && ex.code === 'FILE_NOT_FOUND') {
        message.error(t('files.errors.fileNotFound'));
      } else {
        message.error(transferErrorMessage(ex, t('files.errors.detailFailed')));
      }
    } finally {
      setDetailLoading(false);
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
    { title: t('files.columns.originalName'), dataIndex: 'originalName', key: 'originalName', minWidth: 220, fixed: 'left', ellipsis: true },
    { title: t('files.columns.size'), dataIndex: 'size', key: 'size', minWidth: 110, render: (v: number) => formatFileSize(Number(v)) },
    { title: t('files.columns.contentType'), dataIndex: 'contentType', key: 'contentType', minWidth: 150, render: (v: string) => <Text code style={{ fontSize: 11 }}>{v}</Text> },
    {
      title: t('files.columns.biz'), key: 'biz', minWidth: 150,
      render: (_, record) => (record.bizType ? <Tag>{`${record.bizType}${record.bizId ? `:${record.bizId}` : ''}`}</Tag> : '—'),
    },
    { title: t('files.columns.createdAt'), dataIndex: 'createdAt', key: 'createdAt', minWidth: 180, render: (v: string) => formatDate(v) },
    {
      title: t('files.columns.actions'), key: 'actions', minWidth: 220, fixed: 'right',
      render: (_, record) => (
        <Space size={4}>
          <Auth code="infra:file:list">
            <Button size="small" loading={detailLoading} onClick={() => void openDetail(record)}>{t('files.actions.detail')}</Button>
          </Auth>
          <Auth code="infra:file:download">
            <Button
              size="small"
              icon={<DownloadOutlined />}
              loading={downloadingId === record.id}
              onClick={() => void download(record)}
            >
              {t('files.actions.download')}
            </Button>
          </Auth>
          <Auth code="infra:file:delete">
            <Popconfirm title={t('files.actions.deleteConfirm', { name: record.originalName })} onConfirm={() => void remove(record)}>
              <Button size="small" danger>{t('files.actions.delete')}</Button>
            </Popconfirm>
          </Auth>
        </Space>
      ),
    },
  ];

  return (
    <>
      <CrudTable<FileView>
        crud={crud}
        tableKey="files"
        title={t('files.title')}
        columns={columns}
        searchPlaceholder={t('files.search.placeholder')}
        reloadAriaLabel={t('files.reload')}
        totalText={(total) => t('files.total', { total })}
      actions={
        <Space size={4} wrap>
          <UploadButton
            label={t('files.actions.upload')}
            onUploaded={() => crud.reload(1)}
          />
          <Auth code="infra:file:download">
            <Button loading={exporting} onClick={() => void exportDemo()}>{t('files.actions.exportExcel')}</Button>
          </Auth>
          <ExcelImportButton
            url={EXCEL_DEMO_ENDPOINTS.import}
            templateUrl={EXCEL_DEMO_ENDPOINTS.template}
            label={t('files.actions.importExcel')}
          />
        </Space>
      }
      />

      <Drawer
        open={detailFile !== null}
        onClose={() => setDetailFile(null)}
        title={t('files.detail.title')}
        width={480}
        destroyOnHidden
      >
        <Descriptions column={1} size="small" bordered>
          <Descriptions.Item label={t('files.detail.id')}><Text code>{detailFile?.id}</Text></Descriptions.Item>
          <Descriptions.Item label={t('files.detail.originalName')}>{detailFile?.originalName}</Descriptions.Item>
          <Descriptions.Item label={t('files.detail.size')}>{detailFile ? formatFileSize(Number(detailFile.size)) : null}</Descriptions.Item>
          <Descriptions.Item label={t('files.detail.contentType')}><Text code style={{ fontSize: 11 }}>{detailFile?.contentType}</Text></Descriptions.Item>
          <Descriptions.Item label={t('files.detail.url')}>
            {detailFile?.url ? <Text code style={{ fontSize: 11 }}>{detailFile.url}</Text> : '—'}
          </Descriptions.Item>
          <Descriptions.Item label={t('files.detail.bizType')}>{detailFile?.bizType ?? '—'}</Descriptions.Item>
          <Descriptions.Item label={t('files.detail.bizId')}>{detailFile?.bizId ?? '—'}</Descriptions.Item>
          <Descriptions.Item label={t('files.detail.createdAt')}>{detailFile ? formatDate(detailFile.createdAt) : null}</Descriptions.Item>
        </Descriptions>
      </Drawer>
    </>
  );
}
