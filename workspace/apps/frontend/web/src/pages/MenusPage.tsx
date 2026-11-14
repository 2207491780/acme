import { useState } from 'react';
import { App, Button, Form, Input, InputNumber, Popconfirm, Select, Space, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ApiError, systemApi, useCrud, useMeStore, type AdminMenuView } from '@acme/core';
import { CrudFormModal } from '../components/CrudFormModal';
import { CrudTable } from '../components/CrudTable';

const { Text } = Typography;

const MENU_TYPES = [
  { value: 'DIRECTORY', label: '目录（容器节点）' },
  { value: 'MENU', label: '菜单（叶子页面）' },
];

interface MenuFormValues {
  parentId?: string;
  menuName: string;
  menuType: string;
  path?: string;
  component?: string;
  icon?: string;
  sortOrder: number;
  visible?: boolean;
  permissionCode?: string;
}

/** 菜单管理（p3-admin）：树形表格全量展示 + 新建/编辑/删除；parent 下拉来自当前树。
 *  迁移 CRUD 范本（REQ-20261114-120101）：useCrud + CrudTable（树表 paged=false、无搜索）+ CrudFormModal。 */
export function MenusPage() {
  const { message } = App.useApp();
  const permissions = useMeStore((s) => s.me?.permissions ?? []);

  const crud = useCrud<AdminMenuView>({
    // 全量树：page/size 仅随请求携带（统一列表分页口径），后端按全量返回；
    // 响应兼容运行时校验：数组（既有口径）或 { records } 分页信封（外部输入不信任）
    fetchPage: async ({ page, size }) => {
      const tree = await systemApi.menuTree(page, size);
      const rows = Array.isArray(tree)
        ? tree
        : ((tree as unknown as { records?: AdminMenuView[] }).records ?? []);
      return { records: rows, total: rows.length };
    },
    permissions,
    errorMessage: '菜单树加载失败',
  });

  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<AdminMenuView | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<MenuFormValues>();

  const openCreate = (parentId?: number) => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ parentId: parentId ? String(parentId) : undefined, sortOrder: 0, menuType: 'MENU', visible: true });
    setCreating(true);
  };

  const openEdit = (record: AdminMenuView) => {
    setEditing(record);
    form.setFieldsValue({
      parentId: record.parentId && record.parentId !== 0 ? String(record.parentId) : undefined,
      menuName: record.menuName,
      menuType: record.menuType,
      path: record.path ?? undefined,
      component: record.component ?? undefined,
      icon: record.icon ?? undefined,
      sortOrder: record.sortOrder ?? 0,
      visible: record.visible ?? true,
      permissionCode: record.permissionCode ?? undefined,
    });
    setCreating(false);
  };

  const submit = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      if (editing) {
        await systemApi.menuUpdate(editing.id, {
          menuName: values.menuName,
          path: values.path,
          component: values.component,
          icon: values.icon,
          sortOrder: values.sortOrder,
          visible: values.visible ?? true,
          permissionCode: values.permissionCode,
          version: editing.version,
        });
        message.success('菜单已更新');
      } else {
        await systemApi.menuCreate({
          parentId: values.parentId ? Number(values.parentId) : null,
          menuName: values.menuName,
          menuType: values.menuType,
          path: values.path,
          component: values.component,
          icon: values.icon,
          sortOrder: values.sortOrder,
          visible: values.visible ?? true,
          permissionCode: values.permissionCode,
        });
        message.success('菜单已创建');
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

  const remove = async (record: AdminMenuView) => {
    try {
      await systemApi.menuDelete(record.id);
      message.success(`菜单 ${record.menuName} 已删除`);
      crud.reload();
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : '删除失败，请稍后重试');
    }
  };

  const columns: ColumnsType<AdminMenuView> = [
    { title: '菜单名称', dataIndex: 'menuName', key: 'menuName' },
    { title: '类型', dataIndex: 'menuType', key: 'menuType', width: 110, render: (v: string) => <Tag color={v === 'DIRECTORY' ? 'processing' : 'success'}>{v === 'DIRECTORY' ? '目录' : '菜单'}</Tag> },
    { title: '路径', dataIndex: 'path', key: 'path', ellipsis: true, render: (v: string | null) => v ?? '—' },
    { title: '组件', dataIndex: 'component', key: 'component', ellipsis: true, render: (v: string | null) => v ?? '—' },
    { title: '权限码', dataIndex: 'permissionCode', key: 'permissionCode', ellipsis: true, render: (v: string | null) => (v ? <Text code>{v}</Text> : '—') },
    { title: '排序', dataIndex: 'sortOrder', key: 'sortOrder', width: 70 },
    {
      title: '可见', dataIndex: 'visible', key: 'visible', width: 80,
      render: (v: boolean | null) => (v === false ? <Tag>隐藏</Tag> : <Tag color="success">可见</Tag>),
    },
    {
      title: '操作', key: 'actions', width: 180,
      render: (_, record) => (
        <Space size={4}>
          {crud.can('system:menu:update') ? <Button size="small" onClick={() => openEdit(record)}>编辑</Button> : null}
          {crud.can('system:menu:create') ? <Button size="small" onClick={() => openCreate(record.id)}>加子项</Button> : null}
          {crud.can('system:menu:delete') ? (
            <Popconfirm title={`确认删除菜单 ${record.menuName}？`} onConfirm={() => void remove(record)}>
              <Button size="small" danger>删除</Button>
            </Popconfirm>
          ) : null}
        </Space>
      ),
    },
  ];

  return (
    <>
      <CrudTable<AdminMenuView>
        crud={crud}
        title="菜单管理"
        columns={columns}
        searchable={false}
        paged={false}
        reloadAriaLabel="刷新菜单树"
        createPermission="system:menu:create"
        createLabel="新建根菜单"
        onCreate={() => openCreate()}
      />

      <CrudFormModal<MenuFormValues>
        open={creating || editing !== null}
        title={editing ? `编辑菜单：${editing.menuName}` : '新建菜单'}
        form={form}
        submitting={submitting}
        onOk={() => void submit()}
        onCancel={() => { setCreating(false); setEditing(null); }}
      >
        <Form.Item name="parentId" label="父级菜单（可选）">
          <Select
            allowClear
            placeholder="不选则为根节点"
            optionFilterProp="label"
            showSearch
            options={flattenMenuOptions(crud.records)}
          />
        </Form.Item>
        <Form.Item name="menuName" label="菜单名称" rules={[{ required: true, message: '请输入菜单名称' }, { min: 1, max: 64, message: '长度 1–64 字符' }]}>
          <Input placeholder="菜单显示名称" />
        </Form.Item>
        <Form.Item name="menuType" label="类型" rules={[{ required: true, message: '请选择类型' }]}>
          <Select options={MENU_TYPES} />
        </Form.Item>
        <Form.Item name="path" label="路径" rules={[{ max: 255, message: '长度不超过 255 字符' }]}>
          <Input placeholder="如 /tenants（叶子页面填路由）" autoComplete="off" />
        </Form.Item>
        <Form.Item name="component" label="组件" rules={[{ max: 255, message: '长度不超过 255 字符' }]}>
          <Input placeholder="选填，组件标识" autoComplete="off" />
        </Form.Item>
        <Form.Item name="icon" label="图标" rules={[{ max: 64, message: '长度不超过 64 字符' }]}>
          <Input placeholder="选填，图标标识" autoComplete="off" />
        </Form.Item>
        <Form.Item name="sortOrder" label="排序" rules={[{ required: true, message: '请输入排序号' }]}>
          <InputNumber min={0} max={9999} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="visible" valuePropName="checked" label="可见">
          <Select
            options={[
              { value: true, label: '可见' },
              { value: false, label: '隐藏' },
            ]}
          />
        </Form.Item>
        <Form.Item name="permissionCode" label="权限码" rules={[{ max: 128, message: '长度不超过 128 字符' }]}>
          <Input placeholder="选填，菜单级权限码" autoComplete="off" />
        </Form.Item>
        {editing ? (
          <Text type="secondary" style={{ fontSize: 12 }}>按乐观锁提交（版本 {editing.version}）；冲突时请刷新后重试。</Text>
        ) : null}
      </CrudFormModal>
    </>
  );
}

/** 展平菜单树为父级下拉选项（含层级缩进）。 */
function flattenMenuOptions(nodes: AdminMenuView[], depth = 0): { value: string; label: string }[] {
  const out: { value: string; label: string }[] = [];
  for (const n of nodes) {
    out.push({ value: String(n.id), label: `${'　'.repeat(depth)}${n.menuName}` });
    if (n.children?.length) {
      out.push(...flattenMenuOptions(n.children, depth + 1));
    }
  }
  return out;
}
