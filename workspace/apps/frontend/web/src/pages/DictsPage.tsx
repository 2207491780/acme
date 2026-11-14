import { useEffect, useState } from 'react';
import { App, Button, Card, Form, Input, InputNumber, Popconfirm, Space, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { PlusOutlined } from '@ant-design/icons';
import { ApiError, systemApi, useCrud, useMeStore, type AdminDictItemView, type AdminDictTypeView } from '@acme/core';
import { CrudFormModal } from '../components/CrudFormModal';
import { CrudTable } from '../components/CrudTable';

const { Text } = Typography;

const CODE_PATTERN = /^[a-z][a-z0-9_-]*$/;

interface TypeFormValues {
  dictCode: string;
  dictName: string;
  remark?: string;
}

interface ItemFormValues {
  itemLabel: string;
  itemValue: string;
  sortOrder: number;
}

/** 字典管理（p3-admin）：主从布局——类型分页表（左）+ 选中类型的条目表（右）。
 *  类型与条目各自 CRUD；类型删除由后端在含条目时拒绝。
 *  迁移 CRUD 范本（REQ-20261114-120101）：类型表走 useCrud + CrudTable（small/行点击选中），
 *  条目表为选中主记录的子清单（无分页/搜索，保持原形态）；两个表单弹窗走 CrudFormModal。 */
export function DictsPage() {
  const { message } = App.useApp();
  const permissions = useMeStore((s) => s.me?.permissions ?? []);

  const crud = useCrud<AdminDictTypeView>({
    fetchPage: ({ page, size }) => systemApi.dictTypeList(page, size),
    permissions,
    pageSize: 20,
    errorMessage: '字典类型加载失败',
  });

  const [selected, setSelected] = useState<AdminDictTypeView | null>(null);
  const [items, setItems] = useState<AdminDictItemView[]>([]);
  const [itemsLoading, setItemsLoading] = useState(false);

  const [typeCreating, setTypeCreating] = useState(false);
  const [typeEditing, setTypeEditing] = useState<AdminDictTypeView | null>(null);
  const [typeForm] = Form.useForm<TypeFormValues>();
  const [typeSubmitting, setTypeSubmitting] = useState(false);

  const [itemCreating, setItemCreating] = useState(false);
  const [itemEditing, setItemEditing] = useState<AdminDictItemView | null>(null);
  const [itemForm] = Form.useForm<ItemFormValues>();
  const [itemSubmitting, setItemSubmitting] = useState(false);

  // 选中态跟随清单：选中项被删/翻页后清空（对齐迁移前 reloadTypes 的回调口径）
  useEffect(() => {
    if (selected && !crud.records.some((t) => t.id === selected.id)) {
      setSelected(null);
      setItems([]);
    }
  }, [crud.records, selected]);

  const loadItems = async (typeId: string) => {
    setItemsLoading(true);
    try {
      const list = await systemApi.dictItemList(typeId);
      // 响应兼容运行时校验：条目端点既有口径为数组；分页信封形状不覆盖原数据
      const rows = Array.isArray(list)
        ? list
        : ((list as unknown as { records?: AdminDictItemView[] }).records ?? []);
      setItems(rows);
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : '条目加载失败');
    } finally {
      setItemsLoading(false);
    }
  };

  const selectType = (record: AdminDictTypeView) => {
    setSelected(record);
    void loadItems(record.id);
  };

  const submitType = async () => {
    const values = await typeForm.validateFields();
    setTypeSubmitting(true);
    try {
      if (typeEditing) {
        await systemApi.dictTypeUpdate(typeEditing.id, {
          dictName: values.dictName,
          remark: values.remark,
          version: typeEditing.version,
        });
        message.success('字典类型已更新');
      } else {
        const created = await systemApi.dictTypeCreate(values);
        message.success('字典类型已创建');
        setSelected(created);
        void loadItems(created.id);
      }
      setTypeEditing(null);
      setTypeCreating(false);
      crud.reload();
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : '操作失败，请稍后重试');
    } finally {
      setTypeSubmitting(false);
    }
  };

  const removeType = async (record: AdminDictTypeView) => {
    try {
      await systemApi.dictTypeDelete(record.id);
      message.success(`字典类型 ${record.dictCode} 已删除`);
      if (selected?.id === record.id) {
        setSelected(null);
        setItems([]);
      }
      crud.reload();
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : '删除失败，请稍后重试');
    }
  };

  const submitItem = async () => {
    if (!selected) return;
    const values = await itemForm.validateFields();
    setItemSubmitting(true);
    try {
      if (itemEditing) {
        await systemApi.dictItemUpdate(itemEditing.id, {
          itemLabel: values.itemLabel,
          itemValue: values.itemValue,
          sortOrder: values.sortOrder,
          version: itemEditing.version,
        });
        message.success('条目已更新');
      } else {
        await systemApi.dictItemCreate(selected.id, values);
        message.success('条目已创建');
      }
      setItemEditing(null);
      setItemCreating(false);
      void loadItems(selected.id);
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : '操作失败，请稍后重试');
    } finally {
      setItemSubmitting(false);
    }
  };

  const removeItem = async (record: AdminDictItemView) => {
    try {
      await systemApi.dictItemDelete(record.id);
      message.success('条目已删除');
      if (selected) void loadItems(selected.id);
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : '删除失败，请稍后重试');
    }
  };

  const typeColumns: ColumnsType<AdminDictTypeView> = [
    { title: '代码', dataIndex: 'dictCode', key: 'dictCode', width: 150 },
    { title: '名称', dataIndex: 'dictName', key: 'dictName' },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 80,
      render: (v: AdminDictTypeView['status']) => <Tag color={v === 'ENABLED' ? 'success' : 'default'}>{v === 'ENABLED' ? '启用' : '停用'}</Tag>,
    },
    {
      title: '操作', key: 'actions', width: 150,
      render: (_, record) => (
        <Space size={4}>
          {crud.can('system:dict:update') ? (
            <Button size="small" onClick={() => { setTypeEditing(record); typeForm.setFieldsValue({ dictCode: record.dictCode, dictName: record.dictName, remark: record.remark ?? undefined }); setTypeCreating(false); }}>
              编辑
            </Button>
          ) : null}
          {crud.can('system:dict:delete') ? (
            <Popconfirm title={`确认删除 ${record.dictCode}？`} onConfirm={() => void removeType(record)}>
              <Button size="small" danger>删除</Button>
            </Popconfirm>
          ) : null}
        </Space>
      ),
    },
  ];

  const itemColumns: ColumnsType<AdminDictItemView> = [
    { title: '标签', dataIndex: 'itemLabel', key: 'itemLabel' },
    { title: '值', dataIndex: 'itemValue', key: 'itemValue', render: (v: string) => <Text code>{v}</Text> },
    { title: '排序', dataIndex: 'sortOrder', key: 'sortOrder', width: 70 },
    {
      title: '操作', key: 'actions', width: 150,
      render: (_, record) => (
        <Space size={4}>
          {crud.can('system:dict:update') ? (
            <Button size="small" onClick={() => { setItemEditing(record); itemForm.setFieldsValue({ itemLabel: record.itemLabel, itemValue: record.itemValue, sortOrder: record.sortOrder }); setItemCreating(false); }}>
              编辑
            </Button>
          ) : null}
          {crud.can('system:dict:delete') ? (
            <Popconfirm title={`确认删除条目 ${record.itemLabel}？`} onConfirm={() => void removeItem(record)}>
              <Button size="small" danger>删除</Button>
            </Popconfirm>
          ) : null}
        </Space>
      ),
    },
  ];

  return (
    <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', alignItems: 'flex-start' }}>
      <div style={{ flex: '1 1 420px', minWidth: 380 }}>
        <CrudTable<AdminDictTypeView>
          crud={crud}
          title="字典类型"
          columns={typeColumns}
          searchable={false}
          size="small"
          reloadAriaLabel="刷新字典类型"
          createPermission="system:dict:create"
          createLabel="新建"
          onCreate={() => { setTypeEditing(null); typeForm.resetFields(); setTypeCreating(true); }}
          onRow={(record) => ({ onClick: () => selectType(record), style: { cursor: 'pointer' } })}
          rowClassName={(record) => (selected?.id === record.id ? 'ant-table-row-selected' : '')}
          footer={<Text type="secondary" style={{ fontSize: 12 }}>点击行选择类型，右侧维护其条目。</Text>}
        />
      </div>

      <Card
        title={selected ? `条目：${selected.dictName}（${selected.dictCode}）` : '字典条目'}
        style={{ flex: '1 1 420px', minWidth: 380 }}
        extra={
          selected && crud.can('system:dict:create') ? (
            <Button type="primary" size="small" icon={<PlusOutlined />} onClick={() => { setItemEditing(null); itemForm.resetFields(); itemForm.setFieldsValue({ sortOrder: 0 }); setItemCreating(true); }}>
              新建条目
            </Button>
          ) : undefined
        }
      >
        {!selected ? (
          <Text type="secondary">先在左侧选择一个字典类型。</Text>
        ) : (
          <Table<AdminDictItemView>
            rowKey="id"
            columns={itemColumns}
            dataSource={items}
            loading={itemsLoading}
            size="small"
            pagination={false}
          />
        )}
      </Card>

      <CrudFormModal<TypeFormValues>
        open={typeCreating || typeEditing !== null}
        title={typeEditing ? `编辑字典类型：${typeEditing.dictCode}` : '新建字典类型'}
        form={typeForm}
        submitting={typeSubmitting}
        onOk={() => void submitType()}
        onCancel={() => { setTypeCreating(false); setTypeEditing(null); }}
      >
        <Form.Item
          name="dictCode"
          label="类型代码"
          rules={[
            { required: true, message: '请输入类型代码' },
            { pattern: CODE_PATTERN, message: '小写字母开头，仅含小写字母/数字/_/-' },
            { min: 2, max: 64, message: '长度 2–64 字符' },
          ]}
          extra={typeEditing ? '类型代码不可修改' : '创建后不可修改'}
        >
          <Input placeholder="如 release_env" disabled={typeEditing !== null} autoComplete="off" />
        </Form.Item>
        <Form.Item name="dictName" label="类型名称" rules={[{ required: true, message: '请输入类型名称' }, { min: 1, max: 64, message: '长度 1–64 字符' }]}>
          <Input placeholder="类型显示名称" />
        </Form.Item>
        <Form.Item name="remark" label="备注" rules={[{ max: 255, message: '长度不超过 255 字符' }]}>
          <Input.TextArea placeholder="选填" rows={2} />
        </Form.Item>
        {typeEditing ? (
          <Text type="secondary" style={{ fontSize: 12 }}>按乐观锁提交（版本 {typeEditing.version}）。</Text>
        ) : null}
      </CrudFormModal>

      <CrudFormModal<ItemFormValues>
        open={itemCreating || itemEditing !== null}
        title={itemEditing ? '编辑条目' : `新建条目（${selected?.dictName ?? ''}）`}
        form={itemForm}
        submitting={itemSubmitting}
        onOk={() => void submitItem()}
        onCancel={() => { setItemCreating(false); setItemEditing(null); }}
      >
        <Form.Item name="itemLabel" label="标签" rules={[{ required: true, message: '请输入标签' }, { min: 1, max: 128, message: '长度 1–128 字符' }]}>
          <Input placeholder="显示标签" />
        </Form.Item>
        <Form.Item name="itemValue" label="值" rules={[{ required: true, message: '请输入值' }, { min: 1, max: 128, message: '长度 1–128 字符' }]}>
          <Input placeholder="存储值" autoComplete="off" />
        </Form.Item>
        <Form.Item name="sortOrder" label="排序" rules={[{ required: true, message: '请输入排序号' }]}>
          <InputNumber min={0} max={9999} style={{ width: '100%' }} />
        </Form.Item>
        {itemEditing ? (
          <Text type="secondary" style={{ fontSize: 12 }}>按乐观锁提交（版本 {itemEditing.version}）。</Text>
        ) : null}
      </CrudFormModal>
    </div>
  );
}
