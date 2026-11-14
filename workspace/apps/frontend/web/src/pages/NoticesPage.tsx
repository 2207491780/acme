import { useCallback, useState } from 'react';
import { App, Button, Form, Input, Popconfirm, Space, Tag } from 'antd';
import type { ColumnsType } from 'antd/es/table';
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

const STATUS_META: Record<NoticeStatus, { color: string; label: string }> = {
  DRAFT: { color: 'default', label: '草稿' },
  PUBLISHED: { color: 'success', label: '已发布' },
  OFFLINE: { color: 'warning', label: '已下线' },
};

export function NoticesPage() {
  const { message: antdMessage } = App.useApp();
  const permissions = useMeStore((s) => s.me?.permissions ?? []);

  const crud = useCrud<AdminNoticeView>({
    fetchPage: ({ page, size, keyword }) => notifyApi.noticeList(page, size, keyword),
    permissions,
    errorMessage: '公告列表加载失败',
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
        antdMessage.success('公告已更新');
      } else {
        await notifyApi.noticeCreate({ title: values.title, content: values.content });
        antdMessage.success('公告已创建');
      }
      closeModal();
      crud.reload();
    } catch (ex: unknown) {
      antdMessage.error(notifyErrorMessage(ex, '保存失败，请稍后重试'));
    } finally {
      setSubmitting(false);
    }
  };

  const runRowAction = async (record: AdminNoticeView, action: () => Promise<unknown>, successText: string, fallback: string) => {
    setActingId(record.id);
    try {
      await action();
      antdMessage.success(successText);
      crud.reload();
    } catch (ex: unknown) {
      // 统一转译（NOTICE_PUBLISHED / NOTICE_NOT_FOUND 等尾部附码）；行保留不刷新
      antdMessage.error(notifyErrorMessage(ex, fallback));
    } finally {
      setActingId(null);
    }
  };

  const columns: ColumnsType<AdminNoticeView> = [
    { title: '标题', dataIndex: 'title', key: 'title' },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 90,
      render: (v: NoticeStatus) => <Tag color={STATUS_META[v]?.color ?? 'default'}>{STATUS_META[v]?.label ?? v}</Tag>,
    },
    {
      title: '发布时间', dataIndex: 'publishAt', key: 'publishAt', width: 170,
      render: (v: string | null) => formatDate(v),
    },
    {
      title: '操作', key: 'actions', width: 230,
      render: (_, record) => (
        <Space size={4}>
          {crud.can('system:notice:update') && record.status !== 'PUBLISHED' ? (
            <Button size="small" onClick={() => openEdit(record)}>编辑</Button>
          ) : null}
          {crud.can('system:notice:publish') && record.status !== 'PUBLISHED' ? (
            <Button
              size="small"
              loading={actingId === record.id}
              onClick={() => void runRowAction(record, () => notifyApi.noticePublish(record.id), '公告已发布，全员站内信已生成', '发布失败，请稍后重试')}
            >
              发布
            </Button>
          ) : null}
          {crud.can('system:notice:publish') && record.status === 'PUBLISHED' ? (
            <Button
              size="small"
              loading={actingId === record.id}
              onClick={() => void runRowAction(record, () => notifyApi.noticeOffline(record.id), '公告已下线', '下线失败，请稍后重试')}
            >
              下线
            </Button>
          ) : null}
          {crud.can('system:notice:delete') ? (
            <Popconfirm title={`确认删除公告「${record.title}」？`} onConfirm={() => void runRowAction(record, () => notifyApi.noticeDelete(record.id), '公告已删除', '删除失败，请稍后重试')}>
              <Button size="small" danger>删除</Button>
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
        title="通知公告"
        columns={columns}
        searchPlaceholder="搜索公告标题"
        reloadAriaLabel="刷新公告"
        createPermission="system:notice:create"
        createLabel="新建"
        onCreate={openCreate}
      />
      <CrudFormModal<NoticeFormValues>
        open={creating || editing !== null}
        title={editing ? '编辑公告' : '新建公告'}
        form={form}
        submitting={submitting}
        onOk={() => void submit()}
        onCancel={closeModal}
        width={680}
      >
        <Form.Item
          name="title"
          label="标题"
          rules={[{ required: true, message: '请输入公告标题' }, { max: 128, message: '长度不超过 128 字符' }]}
        >
          <Input placeholder="公告标题" autoComplete="off" />
        </Form.Item>
        <Form.Item
          name="content"
          label="正文"
          rules={[{ required: true, message: '请输入公告正文' }]}
        >
          <RichTextEditor />
        </Form.Item>
        {editing ? (
          <div style={{ marginBottom: 16, fontSize: 12, color: 'var(--muted)' }}>
            按乐观锁提交（版本 {editing.version}）。
          </div>
        ) : null}
      </CrudFormModal>
    </div>
  );
}
