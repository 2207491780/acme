import { useEffect, useState } from 'react';
import { App, Button, Card, Form, Input, InputNumber, Popconfirm, Space, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { ApiError, Auth, systemApi, useCrud, useMeStore, type AdminDictItemView, type AdminDictTypeView } from '@acme/core';
import { CrudFormModal } from '../components/CrudFormModal';
import { CrudTable } from '../components/CrudTable';
import { TableSettingsButton, useTableSettings } from '../components/tableSettings';

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
  const { t } = useTranslation();
  const { message } = App.useApp();
  const permissions = useMeStore((s) => s.me?.permissions) ?? [];

  const crud = useCrud<AdminDictTypeView>({
    fetchPage: ({ page, size, signal }) => systemApi.dictTypeList(page, size, undefined, signal),
    permissions,
    pageSize: 20,
    errorMessage: t('dicts.errors.typesLoadFailed'),
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
    if (selected && !crud.records.some((type) => type.id === selected.id)) {
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
      message.error(ex instanceof ApiError ? ex.message : t('dicts.errors.itemsLoadFailed'));
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
        message.success(t('dicts.types.form.updated'));
      } else {
        const created = await systemApi.dictTypeCreate(values);
        message.success(t('dicts.types.form.created'));
        setSelected(created);
        void loadItems(created.id);
      }
      setTypeEditing(null);
      setTypeCreating(false);
      crud.reload();
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : t('common.errors.operationFailed'));
    } finally {
      setTypeSubmitting(false);
    }
  };

  const removeType = async (record: AdminDictTypeView) => {
    try {
      await systemApi.dictTypeDelete(record.id);
      message.success(t('dicts.types.deleted', { code: record.dictCode }));
      if (selected?.id === record.id) {
        setSelected(null);
        setItems([]);
      }
      crud.reload();
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : t('common.errors.deleteFailed'));
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
        message.success(t('dicts.items.updated'));
      } else {
        await systemApi.dictItemCreate(selected.id, values);
        message.success(t('dicts.items.created'));
      }
      setItemEditing(null);
      setItemCreating(false);
      void loadItems(selected.id);
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : t('common.errors.operationFailed'));
    } finally {
      setItemSubmitting(false);
    }
  };

  const removeItem = async (record: AdminDictItemView) => {
    try {
      await systemApi.dictItemDelete(record.id);
      message.success(t('dicts.items.deleted'));
      if (selected) void loadItems(selected.id);
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : t('common.errors.deleteFailed'));
    }
  };

  const typeColumns: ColumnsType<AdminDictTypeView> = [
    { title: t('dicts.types.columns.dictCode'), dataIndex: 'dictCode', key: 'dictCode', minWidth: 150, fixed: 'left' },
    { title: t('dicts.types.columns.dictName'), dataIndex: 'dictName', key: 'dictName', minWidth: 160 },
    {
      title: t('dicts.types.columns.status'), dataIndex: 'status', key: 'status', minWidth: 80,
      render: (v: AdminDictTypeView['status']) => <Tag color={v === 'ENABLED' ? 'success' : 'default'}>{v === 'ENABLED' ? t('common.status.enabled') : t('common.status.disabled')}</Tag>,
    },
    {
      title: t('dicts.types.columns.actions'), key: 'actions', minWidth: 150, fixed: 'right',
      render: (_, record) => (
        <Space size={4}>
          <Auth code="system:dict:update">
            <Button size="small" onClick={() => { setTypeEditing(record); typeForm.setFieldsValue({ dictCode: record.dictCode, dictName: record.dictName, remark: record.remark ?? undefined }); setTypeCreating(false); }}>
              {t('common.actions.edit')}
            </Button>
          </Auth>
          <Auth code="system:dict:delete">
            <Popconfirm title={t('dicts.types.deleteConfirm', { code: record.dictCode })} onConfirm={() => void removeType(record)}>
              <Button size="small" danger>{t('common.actions.delete')}</Button>
            </Popconfirm>
          </Auth>
        </Space>
      ),
    },
  ];

  const itemColumns: ColumnsType<AdminDictItemView> = [
    { title: t('dicts.items.columns.itemLabel'), dataIndex: 'itemLabel', key: 'itemLabel', minWidth: 160, fixed: 'left' },
    { title: t('dicts.items.columns.itemValue'), dataIndex: 'itemValue', key: 'itemValue', minWidth: 140, render: (v: string) => <Text code>{v}</Text> },
    { title: t('dicts.items.columns.sortOrder'), dataIndex: 'sortOrder', key: 'sortOrder', minWidth: 70 },
    {
      title: t('dicts.items.columns.actions'), key: 'actions', minWidth: 150, fixed: 'right',
      render: (_, record) => (
        <Space size={4}>
          <Auth code="system:dict:update">
            <Button size="small" onClick={() => { setItemEditing(record); itemForm.setFieldsValue({ itemLabel: record.itemLabel, itemValue: record.itemValue, sortOrder: record.sortOrder }); setItemCreating(false); }}>
              {t('common.actions.edit')}
            </Button>
          </Auth>
          <Auth code="system:dict:delete">
            <Popconfirm title={t('dicts.items.deleteConfirm', { label: record.itemLabel })} onConfirm={() => void removeItem(record)}>
              <Button size="small" danger>{t('common.actions.delete')}</Button>
            </Popconfirm>
          </Auth>
        </Space>
      ),
    },
  ];

  const itemSettings = useTableSettings('dictItems', itemColumns, items);

  return (
    <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', alignItems: 'flex-start' }}>
      <div style={{ flex: '1 1 420px', minWidth: 380 }}>
        <CrudTable<AdminDictTypeView>
          crud={crud}
          tableKey="dictTypes"
          title={t('dicts.types.title')}
          columns={typeColumns}
          searchable={false}
          size="small"
          reloadAriaLabel={t('dicts.types.reload')}
          createPermission="system:dict:create"
          createLabel={t('dicts.types.create')}
          onCreate={() => { setTypeEditing(null); typeForm.resetFields(); setTypeCreating(true); }}
          onRow={(record) => ({ onClick: () => selectType(record), style: { cursor: 'pointer' } })}
          rowClassName={(record) => (selected?.id === record.id ? 'ant-table-row-selected' : '')}
          footer={<Text type="secondary" style={{ fontSize: 12 }}>{t('dicts.types.footer')}</Text>}
        />
      </div>

      <Card
        title={selected ? t('dicts.items.selectedTitle', { name: selected.dictName, code: selected.dictCode }) : t('dicts.items.title')}
        style={{ flex: '1 1 420px', minWidth: 380 }}
        extra={
          <Space>
            <TableSettingsButton control={itemSettings} columns={itemColumns} paged={false} />
            <Auth code="system:dict:create">
              {selected ? (
                <Button type="primary" size="small" icon={<PlusOutlined />} onClick={() => { setItemEditing(null); itemForm.resetFields(); itemForm.setFieldsValue({ sortOrder: 0 }); setItemCreating(true); }}>
                  {t('dicts.items.create')}
                </Button>
              ) : null}
            </Auth>
          </Space>
        }
      >
        {!selected ? (
          <Text type="secondary">{t('dicts.items.pickFirst')}</Text>
        ) : (
          <Table<AdminDictItemView>
            rowKey="id"
            columns={itemSettings.enhancedColumns}
            bordered={itemSettings.settings.bordered}
            dataSource={items}
            loading={itemsLoading}
            size="small"
            pagination={false}
            scroll={{ x: 'max-content' }}
          />
        )}
      </Card>

      <CrudFormModal<TypeFormValues>
        open={typeCreating || typeEditing !== null}
        title={typeEditing ? t('dicts.types.form.editTitle', { code: typeEditing.dictCode }) : t('dicts.types.form.createTitle')}
        form={typeForm}
        submitting={typeSubmitting}
        onOk={() => void submitType()}
        onCancel={() => { setTypeCreating(false); setTypeEditing(null); }}
      >
        <Form.Item
          name="dictCode"
          label={t('dicts.types.form.code')}
          rules={[
            { required: true, message: t('dicts.types.form.codeRequired') },
            { pattern: CODE_PATTERN, message: t('common.validation.codePattern') },
            { min: 2, max: 64, message: t('common.validation.lengthRange', { min: 2, max: 64 }) },
          ]}
          extra={typeEditing ? t('dicts.types.form.codeExtra') : t('common.validation.immutableAfterCreate')}
        >
          <Input placeholder={t('dicts.types.form.codePlaceholder')} disabled={typeEditing !== null} autoComplete="off" />
        </Form.Item>
        <Form.Item name="dictName" label={t('dicts.types.form.name')} rules={[{ required: true, message: t('dicts.types.form.nameRequired') }, { min: 1, max: 64, message: t('common.validation.lengthRange', { min: 1, max: 64 }) }]}>
          <Input placeholder={t('dicts.types.form.namePlaceholder')} />
        </Form.Item>
        <Form.Item name="remark" label={t('dicts.types.form.remark')} rules={[{ max: 255, message: t('common.validation.maxLength', { max: 255 }) }]}>
          <Input.TextArea placeholder={t('common.validation.optional')} rows={2} />
        </Form.Item>
        {typeEditing ? (
          <Text type="secondary" style={{ fontSize: 12 }}>{t('common.validation.optimisticLock', { version: typeEditing.version })}。</Text>
        ) : null}
      </CrudFormModal>

      <CrudFormModal<ItemFormValues>
        open={itemCreating || itemEditing !== null}
        title={itemEditing ? t('dicts.items.editTitle') : t('dicts.items.createTitle', { name: selected?.dictName ?? '' })}
        form={itemForm}
        submitting={itemSubmitting}
        onOk={() => void submitItem()}
        onCancel={() => { setItemCreating(false); setItemEditing(null); }}
      >
        <Form.Item name="itemLabel" label={t('dicts.items.label')} rules={[{ required: true, message: t('dicts.items.labelRequired') }, { min: 1, max: 128, message: t('common.validation.lengthRange', { min: 1, max: 128 }) }]}>
          <Input placeholder={t('dicts.items.labelPlaceholder')} />
        </Form.Item>
        <Form.Item name="itemValue" label={t('dicts.items.value')} rules={[{ required: true, message: t('dicts.items.valueRequired') }, { min: 1, max: 128, message: t('common.validation.lengthRange', { min: 1, max: 128 }) }]}>
          <Input placeholder={t('dicts.items.valuePlaceholder')} autoComplete="off" />
        </Form.Item>
        <Form.Item name="sortOrder" label={t('dicts.items.sortOrder')} rules={[{ required: true, message: t('dicts.items.sortOrderRequired') }]}>
          <InputNumber min={0} max={9999} style={{ width: '100%' }} />
        </Form.Item>
        {itemEditing ? (
          <Text type="secondary" style={{ fontSize: 12 }}>{t('common.validation.optimisticLock', { version: itemEditing.version })}。</Text>
        ) : null}
      </CrudFormModal>
    </div>
  );
}
