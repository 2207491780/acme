import { useCallback, useState } from 'react';
import { App, Button, Form, Input, Popconfirm, Space, Tag } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import { formatDate, notifyApi, notifyErrorMessage, useCrud, useMeStore } from '@acme/core';
import type { AdminNoticeView, NoticeStatus } from '@acme/core';
import { CrudFormModal } from '../components/CrudFormModal';
import { CrudTable } from '../components/CrudTable';
import { RichTextEditor } from '../components/RichTextEditor';

/**
 * 通知公告管理（REQ-20261114-120102，AC-12/裁决⑤⑫）：CRUD 对齐 CrudTable/CrudFormModal 范本，
 * 正文经 RichTextEditor（tiptap）承载富文本 HTML（保存/回显一致）；
 * 生命周期 DRAFT→PUBLISHED→OFFLINE：发布扇出全员站内信；PUBLISHED 行可下线（再发布=新一轮扇出）；
 * 已发布删除由后端拒绝（NOTICE_PUBLISHED → 统一转译反馈，行保留）。
 */

interface NoticeFormValues {
  title: string;
  content: string;
}

export function NoticesPage() {
  const { t } = useTranslation();
  const { message: antdMessage } = App.useApp();
  const permissions = useMeStore((s) => s.me?.permissions) ?? [];

  const crud = useCrud<AdminNoticeView>({
    fetchPage: ({ page, size, keyword, signal }) => notifyApi.noticeList(page, size, keyword, signal),
    permissions,
    errorMessage: t('notices.errors.loadFailed'),
  });

  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<AdminNoticeView | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [actingId, setActingId] = useState<string | null>(null);
  const [form] = Form.useForm<NoticeFormValues>();

  const openCreate = useCallback(() => {
    setEditing(null);
    form.resetFields();
    setCreating(true);
  }, [form]);

  const openEdit = useCallback((record: AdminNoticeView) => {
    setEditing(record);
    form.setFieldsValue({ title: record.title, content: record.content });
    setCreating(false);
  }, [form]);

  const closeModal = useCallback(() => {
    setCreating(false);
    setEditing(null);
  }, []);

  const submit = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      if (editing) {
        await notifyApi.noticeUpdate(editing.id, { title: values.title, content: values.content, version: editing.version });
        antdMessage.success(t('notices.form.updated'));
      } else {
        await notifyApi.noticeCreate({ title: values.title, content: values.content });
        antdMessage.success(t('notices.form.created'));
      }
      closeModal();
      crud.reload();
    } catch (ex: unknown) {
      antdMessage.error(notifyErrorMessage(ex, t('notices.toast.saveFailed')));
    } finally {
      setSubmitting(false);
    }
  };

  const runRowAction = async (record: AdminNoticeView, action: () => Promise<unknown>, successKey: string, fallbackKey: string) => {
    setActingId(record.id);
    try {
      await action();
      antdMessage.success(t(successKey));
      crud.reload();
    } catch (ex: unknown) {
      // 统一转译（NOTICE_PUBLISHED / NOTICE_NOT_FOUND 等尾部附码）；行保留不刷新
      antdMessage.error(notifyErrorMessage(ex, t(fallbackKey)));
    } finally {
      setActingId(null);
    }
  };

  const columns: ColumnsType<AdminNoticeView> = [
    { title: t('notices.columns.title'), dataIndex: 'title', key: 'title', minWidth: 200, fixed: 'left' },
    {
      title: t('notices.columns.status'), dataIndex: 'status', key: 'status', minWidth: 90,
      render: (v: NoticeStatus) => {
        const meta = {
          DRAFT: { color: 'default' },
          PUBLISHED: { color: 'success' },
          OFFLINE: { color: 'warning' },
        } as Record<NoticeStatus, { color: string }>;
        return <Tag color={meta[v]?.color ?? 'default'}>{t(`notices.status.${v}`)}</Tag>;
      },
    },
    {
      title: t('notices.columns.publishAt'), dataIndex: 'publishAt', key: 'publishAt', minWidth: 170,
      render: (v: string | null) => formatDate(v),
    },
    {
      title: t('notices.columns.actions'), key: 'actions', minWidth: 230, fixed: 'right',
      render: (_, record) => (
        <Space size={4}>
          {crud.can('system:notice:update') && record.status !== 'PUBLISHED' ? (
            <Button size="small" onClick={() => openEdit(record)}>{t('notices.actions.edit')}</Button>
          ) : null}
          {crud.can('system:notice:publish') && record.status !== 'PUBLISHED' ? (
            <Button
              size="small"
              loading={actingId === record.id}
              onClick={() => void runRowAction(record, () => notifyApi.noticePublish(record.id), 'notices.toast.publishSuccess', 'notices.toast.publishFailed')}
            >
              {t('notices.actions.publish')}
            </Button>
          ) : null}
          {crud.can('system:notice:publish') && record.status === 'PUBLISHED' ? (
            <Button
              size="small"
              loading={actingId === record.id}
              onClick={() => void runRowAction(record, () => notifyApi.noticeOffline(record.id), 'notices.toast.offlineSuccess', 'notices.toast.offlineFailed')}
            >
              {t('notices.actions.offline')}
            </Button>
          ) : null}
          {crud.can('system:notice:delete') ? (
            <Popconfirm title={t('notices.actions.deleteConfirm', { title: record.title })} onConfirm={() => void runRowAction(record, () => notifyApi.noticeDelete(record.id), 'notices.toast.deleteSuccess', 'notices.toast.deleteFailed')}>
              <Button size="small" danger>{t('notices.actions.delete')}</Button>
            </Popconfirm>
          ) : null}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <CrudTable<AdminNoticeView>
        crud={crud}
        title={t('notices.title')}
        columns={columns}
        searchPlaceholder={t('notices.search.placeholder')}
        reloadAriaLabel={t('notices.reload')}
        createPermission="system:notice:create"
        createLabel={t('notices.create')}
        onCreate={openCreate}
      />
      <CrudFormModal<NoticeFormValues>
        open={creating || editing !== null}
        title={editing ? t('notices.form.editTitle') : t('notices.form.createTitle')}
        form={form}
        submitting={submitting}
        onOk={() => void submit()}
        onCancel={closeModal}
        width={680}
      >
        <Form.Item
          name="title"
          label={t('notices.form.title')}
          rules={[{ required: true, message: t('notices.form.titleRequired') }, { max: 128, message: t('common.validation.maxLength', { max: 128 }) }]}
        >
          <Input placeholder={t('notices.form.titlePlaceholder')} autoComplete="off" />
        </Form.Item>
        <Form.Item
          name="content"
          label={t('notices.form.content')}
          rules={[{ required: true, message: t('notices.form.contentRequired') }]}
        >
          <RichTextEditor />
        </Form.Item>
        {editing ? (
          <div style={{ marginBottom: 16, fontSize: 12, color: 'var(--muted)' }}>
            {t('notices.form.lockNote', { version: editing.version })}
          </div>
        ) : null}
      </CrudFormModal>
    </div>
  );
}
