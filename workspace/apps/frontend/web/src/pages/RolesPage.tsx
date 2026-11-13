import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, App, Button, Card, Checkbox, Form, Input, Modal, Popconfirm, Select, Space, Table, Tag, Tree, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { ApiError } from '../api/client';
import { systemApi, type AdminMenuView, type AdminPermissionView, type AdminRoleView } from '../api/system';
import { useMeStore } from '../store/meStore';

const { Text } = Typography;

const CODE_PATTERN = /^[a-z][a-z0-9_-]*$/;

interface RoleFormValues {
  parentId?: string;
  roleCode: string;
  roleName: string;
  remark?: string;
}

/** 角色管理（p3-admin）：分页/搜索/新建/编辑/启停/删除 + 权限点绑定抽屉（ROLE_GRANT）。
 *  菜单绑定同抽屉形态（tree 勾选）随本页第二抽屉交付。 */
export function RolesPage() {
  const { message } = App.useApp();
  const permissions = useMeStore((s) => s.me?.permissions ?? []);
  const can = (code: string) => permissions.includes(code);

  const [records, setRecords] = useState<AdminRoleView[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size] = useState(10);
  const [keyword, setKeyword] = useState('');
  const [queryVersion, setQueryVersion] = useState(0);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<AdminRoleView | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<RoleFormValues>();

  const [granting, setGranting] = useState<AdminRoleView | null>(null);
  const [grantKind, setGrantKind] = useState<'permissions' | 'menus'>('permissions');
  const [catalog, setCatalog] = useState<AdminPermissionView[]>([]);
  const [grantedIds, setGrantedIds] = useState<string[]>([]);
  const [grantLoading, setGrantLoading] = useState(false);

  // 列表请求竞态防护：过期响应按序号丢弃（review 修复）
  const reloadSeq = useRef(0);
  const reload = useCallback((targetPage = page) => {
    const seq = ++reloadSeq.current;
    setLoading(true);
    setLoadError(null);
    systemApi.roleList(targetPage, size, keyword || undefined)
      .then((result) => {
        if (seq !== reloadSeq.current) return;
        setRecords(result.records);
        if (result.records.length === 0 && targetPage > 1 && Number(result.total) > 0) {
          setPage(targetPage - 1);
          return;
        }
        setTotal(Number(result.total));
      })
      .catch((ex: unknown) => {
        if (seq !== reloadSeq.current) return;
        setLoadError(ex instanceof ApiError ? ex.message : '角色清单加载失败');
      })
      .finally(() => {
        if (seq === reloadSeq.current) setLoading(false);
      });
  }, [page, size, keyword, queryVersion]);

  useEffect(() => {
    reload(page);
  }, [page, queryVersion]);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    setCreating(true);
  };

  const openEdit = (record: AdminRoleView) => {
    setEditing(record);
    form.setFieldsValue({
      parentId: record.parentId ?? undefined,
      roleCode: record.roleCode,
      roleName: record.roleName,
      remark: record.remark ?? undefined,
    });
    setCreating(false);
  };

  const submit = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      if (editing) {
        await systemApi.roleUpdate(editing.id, {
          roleName: values.roleName,
          remark: values.remark,
          parentId: values.parentId ? Number(values.parentId) : null,
          version: editing.version,
        });
        message.success('角色已更新');
      } else {
        await systemApi.roleCreate({
          parentId: values.parentId ? Number(values.parentId) : null,
          roleCode: values.roleCode,
          roleName: values.roleName,
          remark: values.remark,
        });
        message.success('角色已创建');
      }
      setEditing(null);
      setCreating(false);
      reload(1);
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : '操作失败，请稍后重试');
    } finally {
      setSubmitting(false);
    }
  };

  const toggleStatus = async (record: AdminRoleView) => {
    const next = record.status === 'ENABLED' ? 'DISABLED' : 'ENABLED';
    try {
      await systemApi.roleStatus(record.id, next);
      message.success(`角色已${next === 'ENABLED' ? '启用' : '停用'}`);
      reload(page);
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : '操作失败，请稍后重试');
    }
  };

  const remove = async (record: AdminRoleView) => {
    try {
      await systemApi.roleDelete(record.id);
      message.success(`角色 ${record.roleCode} 已删除`);
      reload(page);
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : '删除失败，请稍后重试');
    }
  };

  /** 打开授权抽屉：加载角色已授予集合 + 目录（权限点全量 / 菜单树）。 */
  const openGrant = async (record: AdminRoleView, kind: 'permissions' | 'menus') => {
    setGranting(record);
    setGrantKind(kind);
    setGrantLoading(true);
    try {
      if (kind === 'permissions') {
        const [granted, all] = await Promise.all([
          systemApi.rolePermissions(record.id),
          systemApi.permissionList(1, 500),
        ]);
        setCatalog(all.records);
        setGrantedIds(granted.map((p) => p.id));
      } else {
        const granted = await systemApi.roleMenus(record.id);
        setGrantedIds(collectTreeIds(granted));
      }
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : '授权数据加载失败');
      setGranting(null);
    } finally {
      setGrantLoading(false);
    }
  };

  const submitGrant = async () => {
    if (!granting) return;
    setSubmitting(true);
    try {
      if (grantKind === 'permissions') {
        await systemApi.roleBindPermissions(granting.id, grantedIds);
        message.success(`角色 ${granting.roleCode} 权限已更新（新权限随下次令牌签发生效）`);
      } else {
        await systemApi.roleBindMenus(granting.id, grantedIds.map(Number));
        message.success(`角色 ${granting.roleCode} 菜单已更新`);
      }
      setGranting(null);
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : '授权失败，请稍后重试');
    } finally {
      setSubmitting(false);
    }
  };

  const columns: ColumnsType<AdminRoleView> = [
    { title: '角色代码', dataIndex: 'roleCode', key: 'roleCode', width: 180 },
    { title: '角色名称', dataIndex: 'roleName', key: 'roleName' },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 90,
      render: (status: AdminRoleView['status']) => (
        <Tag color={status === 'ENABLED' ? 'success' : 'default'}>{status === 'ENABLED' ? '启用' : '停用'}</Tag>
      ),
    },
    { title: '备注', dataIndex: 'remark', key: 'remark', ellipsis: true, render: (v: string | null) => v ?? '—' },
    {
      title: '操作', key: 'actions', width: 320,
      render: (_, record) => (
        <Space size={4}>
          {can('ROLE_UPDATE') ? <Button size="small" onClick={() => openEdit(record)}>编辑</Button> : null}
          {can('ROLE_GRANT') ? (
            <Button size="small" onClick={() => void openGrant(record, 'permissions')}>权限</Button>
          ) : null}
          {can('ROLE_GRANT') ? (
            <Button size="small" onClick={() => void openGrant(record, 'menus')}>菜单</Button>
          ) : null}
          {can('ROLE_UPDATE') ? (
            <Popconfirm title={`确认${record.status === 'ENABLED' ? '停用' : '启用'} ${record.roleName}？`} onConfirm={() => void toggleStatus(record)}>
              <Button size="small" danger={record.status === 'ENABLED'}>{record.status === 'ENABLED' ? '停用' : '启用'}</Button>
            </Popconfirm>
          ) : null}
          {can('ROLE_DELETE') && !record.builtIn ? (
            <Popconfirm title={`确认删除角色 ${record.roleCode}？`} onConfirm={() => void remove(record)}>
              <Button size="small" danger>删除</Button>
            </Popconfirm>
          ) : null}
          {record.builtIn ? <Text type="secondary" style={{ fontSize: 12 }}>内置</Text> : null}
        </Space>
      ),
    },
  ];

  return (
    <Card
      title="角色管理"
      extra={
        <Space>
          <Input.Search
            placeholder="搜索角色代码 / 名称"
            allowClear
            style={{ width: 220 }}
            onSearch={(value) => {
              setKeyword(value.trim());
              setPage(1);
              setQueryVersion((v) => v + 1);
            }}
          />
          <Button icon={<ReloadOutlined />} onClick={() => reload(page)} aria-label="刷新角色清单" />
          {can('ROLE_CREATE') ? (
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建角色</Button>
          ) : null}
        </Space>
      }
    >
      {loadError && (
        <Alert type="error" showIcon style={{ marginBottom: 16 }} message={loadError}
          action={<Button size="small" onClick={() => reload(page)}>重试</Button>} />
      )}
      <Table<AdminRoleView>
        rowKey="id"
        columns={columns}
        dataSource={records}
        loading={loading}
        pagination={{ current: page, pageSize: size, total, showTotal: (t) => `共 ${t} 个角色`, onChange: (p) => setPage(p) }}
      />

      <Modal
        open={creating || editing !== null}
        title={editing ? `编辑角色：${editing.roleCode}` : '新建角色'}
        onCancel={() => { setCreating(false); setEditing(null); }}
        onOk={() => void submit()}
        confirmLoading={submitting}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item name="parentId" label="父级角色（可选）">
            <Select
              allowClear
              placeholder="不选则为顶级角色"
              options={records.filter((r) => r.id !== editing?.id).map((r) => ({ value: r.id, label: r.roleName }))}
              optionFilterProp="label"
              showSearch
            />
          </Form.Item>
          <Form.Item
            name="roleCode"
            label="角色代码"
            rules={[
              { required: true, message: '请输入角色代码' },
              { pattern: CODE_PATTERN, message: '小写字母开头，仅含小写字母/数字/_/-' },
              { min: 2, max: 64, message: '长度 2–64 字符' },
            ]}
            extra={editing ? '角色代码不可修改' : '创建后不可修改'}
          >
            <Input placeholder="如 ops_lead" disabled={editing !== null} autoComplete="off" />
          </Form.Item>
          <Form.Item
            name="roleName"
            label="角色名称"
            rules={[
              { required: true, message: '请输入角色名称' },
              { min: 1, max: 64, message: '长度 1–64 字符' },
            ]}
          >
            <Input placeholder="角色显示名称" />
          </Form.Item>
          <Form.Item name="remark" label="备注" rules={[{ max: 255, message: '长度不超过 255 字符' }]}>
            <Input.TextArea placeholder="选填" rows={2} />
          </Form.Item>
          {editing ? (
            <Text type="secondary" style={{ fontSize: 12 }}>按乐观锁提交（版本 {editing.version}）；冲突时请刷新后重试。</Text>
          ) : null}
        </Form>
      </Modal>

      <Modal
        open={granting !== null}
        title={
          granting
            ? `${grantKind === 'permissions' ? '权限点' : '菜单'}授权：${granting.roleName}`
            : ''
        }
        width={grantKind === 'menus' ? 560 : 640}
        onCancel={() => setGranting(null)}
        onOk={() => void submitGrant()}
        confirmLoading={submitting || grantLoading}
        okButtonProps={{ disabled: grantLoading }}
        destroyOnClose
      >
        {grantKind === 'permissions' ? (
          <Checkbox.Group style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8, maxHeight: 420, overflow: 'auto' }} value={grantedIds} onChange={(v) => setGrantedIds(v as string[])}>
            {catalog.map((p) => (
              <Checkbox key={p.id} value={p.id}>{`${p.permissionName}（${p.permissionCode}）`}</Checkbox>
            ))}
          </Checkbox.Group>
        ) : (
          <div style={{ maxHeight: 420, overflow: 'auto' }}>
            <RoleMenuTree checkedIds={grantedIds} onChange={setGrantedIds} />
          </div>
        )}
        <Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 12 }}>
          提交即全量覆盖；权限变更随下次令牌签发生效。
        </Text>
      </Modal>
    </Card>
  );
}

/** 递归收集菜单树全部节点 id（授权抽屉树形勾选的全集）。 */
function collectTreeIds(nodes: AdminMenuView[]): string[] {
  const out: string[] = [];
  const walk = (list: AdminMenuView[]) => {
    for (const n of list) {
      out.push(String(n.id));
      if (n.children?.length) walk(n.children);
    }
  };
  walk(nodes);
  return out;
}

/** 角色菜单树形勾选（antd Tree checkable；fieldNames 对齐 AdminMenuView）。 */
function RoleMenuTree({ checkedIds, onChange }: { checkedIds: string[]; onChange: (ids: string[]) => void }) {
  const [tree, setTree] = useState<{ id: number; menuName: string; children?: unknown }[]>([]);
  useEffect(() => {
    systemApi.menuTree()
      .then(setTree)
      .catch(() => setTree([]));
  }, []);
  return (
    <Tree
      checkable
      checkStrictly
      selectable={false}
      defaultExpandAll
      blockNode
      treeData={tree as never[]}
      fieldNames={{ title: 'menuName', key: 'id', children: 'children' }}
      checkedKeys={checkedIds}
      onCheck={(checked) => {
        const shape = checked as { checked: (string | number)[] };
        onChange(shape.checked.map(String));
      }}
    />
  );
}
