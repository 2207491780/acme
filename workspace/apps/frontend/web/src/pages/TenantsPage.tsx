import { useEffect, useMemo, useState } from 'react';
import { App, Button, DatePicker, Form, Input, Popconfirm, Select, Space, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import { ApiError, Auth, formatDate, systemApi, tenantPackageApi, useCrud, useMeStore, type AdminTenantPackageView, type AdminTenantView } from '@acme/core';
import { CrudFormModal } from '../components/CrudFormModal';
import { CrudTable } from '../components/CrudTable';

const { Text } = Typography;

const CODE_PATTERN = /^[a-z][a-z0-9_-]*$/;

interface TenantFormValues {
  tenantCode: string;
  tenantName: string;
  remark?: string;
  /** 联系人/联系电话/有效期（REQ-20261117-025200；expiresAt 表单态为 Dayjs，提交转 ISO 字符串，清空=null 长期）。 */
  contactName?: string;
  contactPhone?: string;
  expiresAt?: Dayjs | null;
  /** 绑定套餐（REQ-20261121-022910；表单态为套餐 id 字符串，清空=不受限，提交转 number|null）。 */
  packageId?: string;
}

/** 租户管理（p2-admin）：分页/搜索/新建/改名/启停；无删除能力——停用代替（FR-007）。
 *  迁移 CRUD 范本（REQ-20261114-120101）：headless 状态机走 @acme/core useCrud，
 *  展示层为 CrudTable + CrudFormModal；动作按钮按 me.permissions 权限码显隐（fail-closed）。 */
export function TenantsPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const permissions = useMeStore((s) => s.me?.permissions) ?? [];

  const crud = useCrud<AdminTenantView>({
    fetchPage: ({ page, size, keyword, signal }) =>
      systemApi.tenantList(page, size, keyword || undefined, signal),
    permissions,
    pageSize: 10,
    errorMessage: t('tenants.errors.loadFailed'),
  });

  // 套餐目录（REQ-20261121-022910）：下拉选项源 + packageId→名称映射（列表列直显兜底）；
  // 拉取失败静默降级（列回退视图自带 packageName / 不受限）
  const [packages, setPackages] = useState<AdminTenantPackageView[]>([]);
  useEffect(() => {
    tenantPackageApi.page({ page: 1, size: 100 })
      .then((result) => setPackages(result.records))
      .catch(() => setPackages([]));
  }, []);
  const packageNameById = useMemo(
    () => new Map(packages.map((pkg) => [pkg.id, pkg.packageName])),
    [packages],
  );

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
    form.setFieldsValue({
      tenantCode: record.tenantCode,
      tenantName: record.tenantName,
      remark: record.remark ?? undefined,
      contactName: record.contactName ?? undefined,
      contactPhone: record.contactPhone ?? undefined,
      expiresAt: record.expiresAt ? dayjs(record.expiresAt) : undefined,
      packageId: record.packageId ?? undefined,
    });
    setCreating(false);
  };

  const submit = async () => {
    const values = await form.validateFields();
    // 三新字段归一：文本 trim 空串→null；expiresAt Dayjs→ISO-8601 字符串，清空=null（长期）
    const profile = {
      contactName: values.contactName?.trim() || null,
      contactPhone: values.contactPhone?.trim() || null,
      expiresAt: values.expiresAt ? values.expiresAt.toISOString() : null,
    };
    setSubmitting(true);
    // 套餐归一（REQ-20261121-022910）：清空=不受限（null=解除绑定）；提交转 number 口径
    const packageId = values.packageId ? Number(values.packageId) : null;
    try {
      if (editing) {
        await systemApi.tenantUpdate(editing.id, {
          tenantName: values.tenantName,
          remark: values.remark,
          ...profile,
          packageId,
          version: editing.version,
        });
        message.success(t('tenants.form.updated'));
      } else {
        await systemApi.tenantCreate({
          tenantCode: values.tenantCode,
          tenantName: values.tenantName,
          remark: values.remark,
          ...profile,
          packageId,
        });
        message.success(t('tenants.form.created'));
      }
      setEditing(null);
      setCreating(false);
      crud.reload(1);
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : t('common.errors.operationFailed'));
    } finally {
      setSubmitting(false);
    }
  };

  const toggleStatus = async (record: AdminTenantView) => {
    const next = record.status === 'ENABLED' ? 'DISABLED' : 'ENABLED';
    try {
      await systemApi.tenantStatus(record.id, next);
      message.success(next === 'ENABLED' ? t('tenants.status.enabled') : t('tenants.status.disabled'));
      crud.reload();
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : t('common.errors.operationFailed'));
    }
  };

  const columns: ColumnsType<AdminTenantView> = [
    { title: t('tenants.columns.tenantCode'), dataIndex: 'tenantCode', key: 'tenantCode', minWidth: 160, fixed: 'left' },
    { title: t('tenants.columns.tenantName'), dataIndex: 'tenantName', key: 'tenantName', minWidth: 160 },
    {
      // 套餐列（REQ-20261121-022910）：视图 packageName 直显 → 本地套餐目录映射兜底 → 未绑定=不受限
      title: t('tenants.columns.package'), dataIndex: 'packageName', key: 'package', minWidth: 140, ellipsis: true,
      render: (_, record) => {
        if (record.packageId) {
          return record.packageName ?? packageNameById.get(String(record.packageId)) ?? '—';
        }
        return t('tenants.columns.unlimited');
      },
    },
    {
      title: t('tenants.columns.status'), dataIndex: 'status', key: 'status', minWidth: 100,
      render: (status: AdminTenantView['status']) => (
        <Tag color={status === 'ENABLED' ? 'success' : 'default'}>{status === 'ENABLED' ? t('common.status.enabled') : t('common.status.disabled')}</Tag>
      ),
    },
    { title: t('tenants.columns.contact'), dataIndex: 'contactName', key: 'contactName', minWidth: 120, ellipsis: true, render: (v: string | null) => v ?? '—' },
    { title: t('tenants.columns.expiresAt'), dataIndex: 'expiresAt', key: 'expiresAt', minWidth: 180, render: (v: string | null) => formatDate(v) },
    { title: t('tenants.columns.remark'), dataIndex: 'remark', key: 'remark', minWidth: 200, ellipsis: true, render: (v: string | null) => v ?? '—' },
    { title: t('tenants.columns.createdAt'), dataIndex: 'createdAt', key: 'createdAt', minWidth: 180, render: (v: string) => formatDate(v) },
    {
      title: t('tenants.columns.actions'), key: 'actions', minWidth: 180, fixed: 'right',
      render: (_, record) => (
        <Space size={4}>
          <Auth code="system:tenant:update">
            <Button size="small" onClick={() => openEdit(record)}>{t('common.actions.edit')}</Button>
          </Auth>
          <Auth code="system:tenant:update">
            <Popconfirm
              title={t('tenants.status.toggleConfirm', { action: record.status === 'ENABLED' ? t('common.status.disabled') : t('common.status.enabled'), code: record.tenantCode })}
              onConfirm={() => void toggleStatus(record)}
            >
              <Button size="small" danger={record.status === 'ENABLED'}>
                {record.status === 'ENABLED' ? t('common.status.disabled') : t('common.status.enabled')}
              </Button>
            </Popconfirm>
          </Auth>
        </Space>
      ),
    },
  ];

  return (
    <>
      <CrudTable<AdminTenantView>
        crud={crud}
        tableKey="tenants"
        title={t('tenants.title')}
        columns={columns}
        searchPlaceholder={t('tenants.search.placeholder')}
        reloadAriaLabel={t('tenants.reload')}
        createPermission="system:tenant:create"
        createLabel={t('tenants.create')}
        onCreate={openCreate}
        totalText={(total) => t('tenants.total', { total })}
      />

      <CrudFormModal<TenantFormValues>
        open={creating || editing !== null}
        title={editing ? t('tenants.form.editTitle', { code: editing.tenantCode }) : t('tenants.form.createTitle')}
        form={form}
        submitting={submitting}
        onOk={() => void submit()}
        onCancel={() => { setCreating(false); setEditing(null); }}
      >
        <Form.Item
          name="tenantCode"
          label={t('tenants.form.code')}
          rules={[
            { required: true, message: t('tenants.form.codeRequired') },
            { pattern: CODE_PATTERN, message: t('common.validation.codePattern') },
            { min: 2, max: 64, message: t('common.validation.lengthRange', { min: 2, max: 64 }) },
          ]}
          extra={editing ? t('tenants.form.codeImmutable') : t('tenants.form.codeExtra')}
        >
          <Input placeholder={t('tenants.form.codePlaceholder')} disabled={editing !== null} autoComplete="off" />
        </Form.Item>
        <Form.Item
          name="tenantName"
          label={t('tenants.form.name')}
          rules={[
            { required: true, message: t('tenants.form.nameRequired') },
            { min: 1, max: 128, message: t('common.validation.lengthRange', { min: 1, max: 128 }) },
          ]}
        >
          <Input placeholder={t('tenants.form.namePlaceholder')} />
        </Form.Item>
        <Form.Item name="remark" label={t('tenants.form.remark')} rules={[{ max: 255, message: t('common.validation.maxLength', { max: 255 }) }]}>
          <Input.TextArea placeholder={t('common.validation.optional')} rows={2} />
        </Form.Item>
        {/* 绑定套餐（REQ-20261121-022910）：可清空=不受限；提交 packageId（null=解除绑定） */}
        <Form.Item name="packageId" label={t('tenants.form.package')} extra={t('tenants.form.packageExtra')}>
          <Select
            allowClear
            placeholder={t('tenants.form.packagePlaceholder')}
            optionFilterProp="label"
            showSearch
            options={packages.map((pkg) => ({ value: pkg.id, label: pkg.packageName }))}
          />
        </Form.Item>
        {/* 租户档案三新字段（REQ-20261117-025200）：联系人/联系电话/有效期（清空=长期） */}
        <Form.Item
          name="contactName"
          label={t('tenants.form.contact')}
          rules={[{ max: 64, message: t('common.validation.maxLength', { max: 64 }) }]}
        >
          <Input placeholder={t('tenants.form.contactPlaceholder')} maxLength={64} autoComplete="off" />
        </Form.Item>
        <Form.Item
          name="contactPhone"
          label={t('tenants.form.contactPhone')}
          rules={[{ max: 32, message: t('common.validation.maxLength', { max: 32 }) }]}
        >
          <Input placeholder={t('tenants.form.contactPhonePlaceholder')} maxLength={32} autoComplete="off" />
        </Form.Item>
        <Form.Item name="expiresAt" label={t('tenants.form.expiresAt')} extra={t('tenants.form.expiresAtExtra')}>
          <DatePicker style={{ width: '100%' }} allowClear placeholder={t('common.validation.optional')} />
        </Form.Item>
        {editing ? (
          <Text type="secondary" style={{ fontSize: 12 }}>
            {t('common.validation.optimisticLock', { version: editing.version })}{t('common.validation.optimisticLockConflict')}
          </Text>
        ) : null}
      </CrudFormModal>
    </>
  );
}
