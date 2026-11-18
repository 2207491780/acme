import { useState } from 'react';
import { App, Button, Form, Input, InputNumber, Popconfirm, Select, Space, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import {
  Auth,
  invalidateDeptTree,
  systemApi,
  systemErrorMessage,
  useCrud,
  useMeStore,
  type AdminDeptView,
} from '@acme/core';
import { CrudFormModal } from '../components/CrudFormModal';
import { CrudTable } from '../components/CrudTable';
import { countDeptTree, flattenDeptOptions } from '../utils/dept';

const { Text } = Typography;

interface DeptFormValues {
  parentId?: string;
  deptName: string;
  sortOrder: number;
  status?: 'ENABLED' | 'DISABLED';
}

/** 部门管理（REQ-20261114-120104）：树形表格全量展示（含禁用态）+ 新建（选上级）/编辑/删除；
 *  上级部门下拉来自当前树；删有子部门/有用户的部门由后端拒绝（DEPT_HAS_CHILDREN/DEPT_HAS_USERS），
 *  统一转译反馈（system-errors，尾部附码）。CRUD 范本对齐 MenusPage（树表 paged=false、无搜索）。 */
export function DeptsPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const permissions = useMeStore((s) => s.me?.permissions) ?? [];

  const crud = useCrud<AdminDeptView>({
    // 全量树：树数组字面口径（契约「树形返回」），响应兼容运行时校验（数组或 { records } 信封）
    fetchPage: async ({ signal }) => {
      const tree = await systemApi.deptTree(signal);
      const rows = Array.isArray(tree)
        ? tree
        : ((tree as unknown as { records?: AdminDeptView[] }).records ?? []);
      return { records: rows, total: countDeptTree(rows) };
    },
    permissions,
    errorMessage: t('depts.errors.loadFailed'),
  });

  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<AdminDeptView | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<DeptFormValues>();

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ sortOrder: 0, status: 'ENABLED' });
    setCreating(true);
  };

  const openEdit = (record: AdminDeptView) => {
    setEditing(record);
    form.setFieldsValue({
      parentId: record.parentId && record.parentId !== '0' ? record.parentId : undefined,
      deptName: record.deptName,
      sortOrder: record.sortOrder ?? 0,
      status: record.status ?? 'ENABLED',
    });
    setCreating(false);
  };

  const submit = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      if (editing) {
        await systemApi.deptUpdate(editing.id, {
          parentId: values.parentId ? Number(values.parentId) : null,
          deptName: values.deptName,
          sortOrder: values.sortOrder,
          status: values.status ?? 'ENABLED',
          version: editing.version,
        });
        message.success(t('depts.form.updated'));
      } else {
        await systemApi.deptCreate({
          parentId: values.parentId ? Number(values.parentId) : null,
          deptName: values.deptName,
          sortOrder: values.sortOrder,
          status: values.status ?? 'ENABLED',
        });
        message.success(t('depts.form.created'));
      }
      setEditing(null);
      setCreating(false);
      invalidateDeptTree();
      crud.reload();
    } catch (ex: unknown) {
      message.error(systemErrorMessage(ex, t('common.errors.operationFailed')));
    } finally {
      setSubmitting(false);
    }
  };

  const remove = async (record: AdminDeptView) => {
    try {
      await systemApi.deptDelete(record.id);
      message.success(t('depts.deleted', { name: record.deptName }));
      invalidateDeptTree();
      crud.reload();
    } catch (ex: unknown) {
      // 删除守卫（DEPT_HAS_CHILDREN/DEPT_HAS_USERS）→ 统一转译反馈；失败不重取（行保留）
      message.error(systemErrorMessage(ex, t('depts.errors.deleteFailed')));
    }
  };

  const columns: ColumnsType<AdminDeptView> = [
    { title: t('depts.columns.deptName'), dataIndex: 'deptName', key: 'deptName', minWidth: 200, fixed: 'left' },
    {
      title: t('depts.columns.status'), dataIndex: 'status', key: 'status', minWidth: 90,
      render: (status: AdminDeptView['status']) => (
        <Tag color={status === 'ENABLED' ? 'success' : 'default'}>{status === 'ENABLED' ? t('common.status.enabled') : t('common.status.disabled')}</Tag>
      ),
    },
    { title: t('depts.columns.sortOrder'), dataIndex: 'sortOrder', key: 'sortOrder', minWidth: 80 },
    {
      title: t('depts.columns.actions'), key: 'actions', minWidth: 160, fixed: 'right',
      render: (_, record) => (
        <Space size={4}>
          <Auth code="system:dept:update">
            <Button size="small" onClick={() => openEdit(record)}>{t('depts.actions.edit')}</Button>
          </Auth>
          <Auth code="system:dept:delete">
            <Popconfirm title={t('depts.actions.deleteConfirm', { name: record.deptName })} onConfirm={() => void remove(record)}>
              <Button size="small" danger>{t('depts.actions.delete')}</Button>
            </Popconfirm>
          </Auth>
        </Space>
      ),
    },
  ];

  return (
    <>
      <CrudTable<AdminDeptView>
        crud={crud}
        tableKey="depts"
        title={t('depts.title')}
        columns={columns}
        searchable={false}
        paged={false}
        reloadAriaLabel={t('depts.reload')}
        createPermission="system:dept:create"
        createLabel={t('depts.create')}
        onCreate={openCreate}
      />

      <CrudFormModal<DeptFormValues>
        open={creating || editing !== null}
        title={editing ? t('depts.form.editTitle', { name: editing.deptName }) : t('depts.form.createTitle')}
        form={form}
        submitting={submitting}
        onOk={() => void submit()}
        onCancel={() => { setCreating(false); setEditing(null); }}
      >
        <Form.Item name="parentId" label={t('depts.form.parent')}>
          <Select
            allowClear
            placeholder={t('depts.form.parentPlaceholder')}
            optionFilterProp="label"
            showSearch
            options={flattenDeptOptions(crud.records, { excludeId: editing ? editing.id : undefined })}
          />
        </Form.Item>
        <Form.Item name="deptName" label={t('depts.form.name')} rules={[{ required: true, message: t('depts.form.nameRequired') }, { min: 1, max: 64, message: t('common.validation.lengthRange', { min: 1, max: 64 }) }]}>
          <Input placeholder={t('depts.form.namePlaceholder')} />
        </Form.Item>
        <Form.Item name="sortOrder" label={t('depts.form.sortOrder')} rules={[{ required: true, message: t('depts.form.sortOrderRequired') }]}>
          <InputNumber min={0} max={9999} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="status" label={t('depts.form.status')}>
          <Select
            options={[
              { value: 'ENABLED', label: t('common.status.enabled') },
              { value: 'DISABLED', label: t('common.status.disabled') },
            ]}
          />
        </Form.Item>
        {editing ? (
          <Text type="secondary" style={{ fontSize: 12 }}>{t('depts.form.lockNote', { version: editing.version })}</Text>
        ) : null}
      </CrudFormModal>
    </>
  );
}
