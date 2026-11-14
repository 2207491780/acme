import { useState } from 'react';
import { App, Button, Form, Input, Popconfirm, Select, Space, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ApiError, systemApi, useCrud, useMeStore, type AdminPermissionView } from '@acme/core';
import { CrudFormModal } from '../components/CrudFormModal';
import { CrudTable } from '../components/CrudTable';

const { Text } = Typography;

const CODE_PATTERN = /^[a-z][a-z0-9:-]*$/;

const PERMISSION_TYPES = [
  { value: 'API', label: 'API（接口级）' },
];

interface PermissionFormValues {
  permissionCode: string;
  permissionName: string;
  permissionType: string;
  httpMethod?: string;
  httpPath?: string;
}

/** 权限点管理（p3-admin）：平台级目录分页 + 新建/编辑/删除。
 *  code 创建后不可改；编辑仅名称与方法/路径（version 乐观锁）。
 *  迁移 CRUD 范本（REQ-20261114-120101）：useCrud + CrudTable/CrudFormModal；本页无关键字搜索（searchable=false）。 */
export function PermissionsPage() {
  const { message } = App.useApp();
  const permissions = useMeStore((s) => s.me?.permissions ?? []);

  const crud = useCrud<AdminPermissionView>({
    fetchPage: ({ page, size }) => systemApi.permissionList(page, size),
    permissions,
    pageSize: 50,
    errorMessage: '权限点清单加载失败',
  });

  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<AdminPermissionView | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<PermissionFormValues>();

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    setCreating(true);
  };

  const openEdit = (record: AdminPermissionView) => {
    setEditing(record);
    form.setFieldsValue({ ...record, httpMethod: record.httpMethod ?? undefined, httpPath: record.httpPath ?? undefined });
    setCreating(false);
  };

  const submit = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      if (editing) {
        await systemApi.permissionUpdate(editing.id, {
          permissionName: values.permissionName,
          httpMethod: values.httpMethod,
          httpPath: values.httpPath,
          version: editing.version,
        });
        message.success('权限点已更新');
      } else {
        await systemApi.permissionCreate(values);
        message.success('权限点已创建');
      }
      setEditing(null);
      setCreating(false);
      crud.reload();
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : '操作失败，请稍后重试');
    } finally {
      setSubmitting(false);
    }
  };

  const remove = async (record: AdminPermissionView) => {
    try {
      await systemApi.permissionDelete(record.id);
      message.success(`权限点 ${record.permissionCode} 已删除`);
      crud.reload();
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : '删除失败，请稍后重试');
    }
  };

  const columns: ColumnsType<AdminPermissionView> = [
    { title: '权限代码', dataIndex: 'permissionCode', key: 'permissionCode', width: 220 },
    { title: '权限名称', dataIndex: 'permissionName', key: 'permissionName' },
    { title: '类型', dataIndex: 'permissionType', key: 'permissionType', width: 110, render: (v: string) => <Tag>{v}</Tag> },
    {
      title: 'HTTP', key: 'http', width: 220,
      render: (_, record) => (record.httpMethod ? <Text code>{record.httpMethod} {record.httpPath}</Text> : '—'),
    },
    {
      title: '操作', key: 'actions', width: 140,
      render: (_, record) => (
        <Space size={4}>
          {crud.can('system:permission:update') ? <Button size="small" onClick={() => openEdit(record)}>编辑</Button> : null}
          {crud.can('system:permission:delete') ? (
            <Popconfirm title={`确认删除权限点 ${record.permissionCode}？`} onConfirm={() => void remove(record)}>
              <Button size="small" danger>删除</Button>
            </Popconfirm>
          ) : null}
        </Space>
      ),
    },
  ];

  return (
    <>
      <CrudTable<AdminPermissionView>
        crud={crud}
        title="权限点管理"
        columns={columns}
        searchable={false}
        reloadAriaLabel="刷新权限点清单"
        createPermission="system:permission:create"
        createLabel="新建权限点"
        onCreate={openCreate}
        totalText={(t) => `共 ${t} 个权限点`}
      />

      <CrudFormModal<PermissionFormValues>
        open={creating || editing !== null}
        title={editing ? `编辑权限点：${editing.permissionCode}` : '新建权限点'}
        form={form}
        submitting={submitting}
        onOk={() => void submit()}
        onCancel={() => { setCreating(false); setEditing(null); }}
      >
        <Form.Item
          name="permissionCode"
          label="权限代码"
          rules={[
            { required: true, message: '请输入权限代码' },
            { pattern: CODE_PATTERN, message: '小写字母开头，仅含小写字母/数字/:/-' },
            { min: 3, max: 128, message: '长度 3–128 字符' },
          ]}
          extra={editing ? '权限代码不可修改' : '如 system:user:create'}
        >
          <Input placeholder="域:资源:动作" disabled={editing !== null} autoComplete="off" />
        </Form.Item>
        <Form.Item
          name="permissionName"
          label="权限名称"
          rules={[
            { required: true, message: '请输入权限名称' },
            { min: 1, max: 64, message: '长度 1–64 字符' },
          ]}
        >
          <Input placeholder="权限显示名称" />
        </Form.Item>
        <Form.Item name="permissionType" label="类型" rules={[{ required: true, message: '请选择类型' }]}>
          <Select options={PERMISSION_TYPES} placeholder="权限类型" />
        </Form.Item>
        <Space.Compact style={{ width: '100%' }}>
          <Form.Item name="httpMethod" label="HTTP 方法" style={{ width: 140 }}>
            <Select
              allowClear
              placeholder="方法"
              options={['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map((m) => ({ value: m, label: m }))}
            />
          </Form.Item>
          <Form.Item name="httpPath" label="HTTP 路径" style={{ flex: 1 }} rules={[{ max: 255, message: '长度不超过 255 字符' }]}>
            <Input placeholder="选填，如 /admin/v1/system/users" autoComplete="off" />
          </Form.Item>
        </Space.Compact>
        {editing ? (
          <Text type="secondary" style={{ fontSize: 12 }}>按乐观锁提交（版本 {editing.version}）；冲突时请刷新后重试。</Text>
        ) : null}
      </CrudFormModal>
    </>
  );
}
