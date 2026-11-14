import { useState } from 'react';
import { App, Button, Form, Input, Popconfirm, Space, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ApiError, formatDate, systemApi, useCrud, useMeStore, type AdminTenantView } from '@acme/core';
import { CrudFormModal } from '../components/CrudFormModal';
import { CrudTable } from '../components/CrudTable';

const { Text } = Typography;

const CODE_PATTERN = /^[a-z][a-z0-9_-]*$/;

interface TenantFormValues {
  tenantCode: string;
  tenantName: string;
  remark?: string;
}

/** 租户管理（p2-admin）：分页/搜索/新建/改名/启停；无删除能力——停用代替（FR-007）。
 *  迁移 CRUD 范本（REQ-20261114-120101）：headless 状态机走 @acme/core useCrud，
 *  展示层为 CrudTable + CrudFormModal；动作按钮按 me.permissions 权限码显隐（fail-closed）。 */
export function TenantsPage() {
  const { message } = App.useApp();
  const permissions = useMeStore((s) => s.me?.permissions ?? []);

  const crud = useCrud<AdminTenantView>({
    fetchPage: ({ page, size, keyword }) => systemApi.tenantList(page, size, keyword || undefined),
    permissions,
    pageSize: 10,
    errorMessage: '租户清单加载失败',
  });

  const [editing, setEditing] = useState<AdminTenantView | null>(null);
  const [creating, setCreating] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<TenantFormValues>();

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    setCreating(true);
  };

  const openEdit = (record: AdminTenantView) => {
    setEditing(record);
    form.setFieldsValue({ tenantCode: record.tenantCode, tenantName: record.tenantName, remark: record.remark ?? undefined });
    setCreating(false);
  };

  const submit = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      if (editing) {
        await systemApi.tenantUpdate(editing.id, {
          tenantName: values.tenantName,
          remark: values.remark,
          version: editing.version,
        });
        message.success('租户已更新');
      } else {
        await systemApi.tenantCreate(values);
        message.success('租户已创建');
      }
      setEditing(null);
      setCreating(false);
      crud.reload(1);
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : '操作失败，请稍后重试');
    } finally {
      setSubmitting(false);
    }
  };

  const toggleStatus = async (record: AdminTenantView) => {
    const next = record.status === 'ENABLED' ? 'DISABLED' : 'ENABLED';
    try {
      await systemApi.tenantStatus(record.id, next);
      message.success(`租户已${next === 'ENABLED' ? '启用' : '停用'}`);
      crud.reload();
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : '操作失败，请稍后重试');
    }
  };

  const columns: ColumnsType<AdminTenantView> = [
    { title: '租户代码', dataIndex: 'tenantCode', key: 'tenantCode', width: 160 },
    { title: '租户名称', dataIndex: 'tenantName', key: 'tenantName' },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 100,
      render: (status: AdminTenantView['status']) => (
        <Tag color={status === 'ENABLED' ? 'success' : 'default'}>{status === 'ENABLED' ? '启用' : '停用'}</Tag>
      ),
    },
    { title: '备注', dataIndex: 'remark', key: 'remark', ellipsis: true, render: (v: string | null) => v ?? '—' },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 180, render: (v: string) => formatDate(v) },
    {
      title: '操作', key: 'actions', width: 180,
      render: (_, record) => (
        <Space size={4}>
          {crud.can('system:tenant:update') ? <Button size="small" onClick={() => openEdit(record)}>编辑</Button> : null}
          {crud.can('system:tenant:update') ? (
            <Popconfirm
              title={`确认${record.status === 'ENABLED' ? '停用' : '启用'}租户 ${record.tenantCode}？`}
              onConfirm={() => void toggleStatus(record)}
            >
              <Button size="small" danger={record.status === 'ENABLED'}>
                {record.status === 'ENABLED' ? '停用' : '启用'}
              </Button>
            </Popconfirm>
          ) : null}
        </Space>
      ),
    },
  ];

  return (
    <>
      <CrudTable<AdminTenantView>
        crud={crud}
        title="租户管理"
        columns={columns}
        searchPlaceholder="搜索租户代码 / 名称"
        searchWidth={240}
        reloadAriaLabel="刷新租户清单"
        createPermission="system:tenant:create"
        createLabel="新建租户"
        onCreate={openCreate}
        totalText={(t) => `共 ${t} 个租户`}
      />

      <CrudFormModal<TenantFormValues>
        open={creating || editing !== null}
        title={editing ? `编辑租户：${editing.tenantCode}` : '新建租户'}
        form={form}
        submitting={submitting}
        onOk={() => void submit()}
        onCancel={() => { setCreating(false); setEditing(null); }}
      >
        <Form.Item
          name="tenantCode"
          label="租户代码"
          rules={[
            { required: true, message: '请输入租户代码' },
            { pattern: CODE_PATTERN, message: '小写字母开头，仅含小写字母/数字/_/-' },
            { min: 2, max: 64, message: '长度 2–64 字符' },
          ]}
          extra={editing ? '租户代码不可修改' : '创建后不可修改（登录与数据隔离标识）'}
        >
          <Input placeholder="如 acme" disabled={editing !== null} autoComplete="off" />
        </Form.Item>
        <Form.Item
          name="tenantName"
          label="租户名称"
          rules={[
            { required: true, message: '请输入租户名称' },
            { min: 1, max: 128, message: '长度 1–128 字符' },
          ]}
        >
          <Input placeholder="租户显示名称" />
        </Form.Item>
        <Form.Item name="remark" label="备注" rules={[{ max: 255, message: '长度不超过 255 字符' }]}>
          <Input.TextArea placeholder="选填" rows={2} />
        </Form.Item>
        {editing ? (
          <Text type="secondary" style={{ fontSize: 12 }}>
            保存按乐观锁提交（版本 {editing.version}）；冲突时请刷新后重试。
          </Text>
        ) : null}
      </CrudFormModal>
    </>
  );
}
