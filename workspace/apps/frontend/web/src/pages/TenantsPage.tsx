import { useEffect, useMemo, useState } from 'react';
import { App, Button, DatePicker, Descriptions, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Spin, Tag, TreeSelect, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import { ApiError, Auth, formatDate, systemApi, TONE_ANTD, tenantPackageApi, useCrud, useMeStore, type AdminTenantPackageView, type AdminTenantTreeNode, type AdminTenantView, type TenantQuotaSource, type TenantUsageView } from '@acme/core';
import { CrudFormModal } from '../components/CrudFormModal';
import { CrudTable } from '../components/CrudTable';

const { Text } = Typography;

const CODE_PATTERN = /^[a-z][a-z0-9_-]*$/;

/** 层级深度上限（REQ-20261122-102954 契约②：1=顶层，level≤3）。 */
const TENANT_MAX_LEVEL = 3;

/** 1 GiB 字节数（配额 Modal GiB↔字节换算口径；后端平台缺省 1 GiB、0=不限）。 */
const BYTES_PER_GIB = 1_073_741_824;

/** 派生视觉态（REQ-20261122-150802 确认②：status 正交优先，其余按 expires/grace 读时派生）。 */
type TenantVisualState = 'active' | 'grace' | 'expired' | 'disabled';

/** 四态派生：已停用（status 正交优先）→ 启用中（expires 未到或无 expires）→ 宽限期
 *  （expires 过且 grace 未过）→ 已过期（宽限也过，或 expires 过且无 grace）。 */
function deriveTenantVisualState(record: AdminTenantView, now: number): TenantVisualState {
  if (record.status === 'DISABLED') {
    return 'disabled';
  }
  const expiresAt = record.expiresAt ? Date.parse(record.expiresAt) : null;
  if (expiresAt === null || Number.isNaN(expiresAt) || expiresAt > now) {
    return 'active';
  }
  const graceUntil = record.graceUntil ? Date.parse(record.graceUntil) : null;
  return graceUntil !== null && !Number.isNaN(graceUntil) && graceUntil > now ? 'grace' : 'expired';
}

/** 四态 → AntD 预设色（UI-SPEC §2.2：语义标签取色走 TONE_ANTD，禁塞 hex）。
 *  启用中=success/宽限期=warning/已过期=danger/已停用=muted。 */
const TENANT_STATE_ANTD: Record<TenantVisualState, string> = {
  active: TONE_ANTD.success,
  grace: TONE_ANTD.warning,
  expired: TONE_ANTD.danger,
  disabled: TONE_ANTD.muted,
};

/** 字节数人类可读（1024 进制，B/KB/MB/GB/TB/PB；三位有效数字）。 */
function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) {
    return '—';
  }
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
  if (bytes === 0) {
    return '0 B';
  }
  const index = Math.min(Math.floor(Math.log2(bytes) / 10), units.length - 1);
  const value = bytes / 1024 ** index;
  const text = value >= 100 ? Math.round(value).toString() : value >= 10 ? value.toFixed(1) : value.toFixed(2);
  return `${text} ${units[index]}`;
}

/** 父租户下拉节点（TreeSelect treeData 形态；显式类型规避递归引用推断）。 */
interface ParentTreeOption {
  value: string;
  title: string;
  children: ParentTreeOption[];
}

interface TenantFormValues {
  tenantCode: string;
  tenantName: string;
  remark?: string;
  /** 联系人/联系电话/有效期（REQ-20261117-025200；expiresAt 表单态为 Dayjs，提交转 ISO 字符串，清空=null 长期）。 */
  contactName?: string;
  contactPhone?: string;
  expiresAt?: Dayjs | null;
  /** 宽限期截止（REQ-20261122-150802 确认⑥：update 收 graceUntil；表单态 Dayjs，清空=不启用宽限）。 */
  graceUntil?: Dayjs | null;
  /** 绑定套餐（REQ-20261121-022910；表单态为套餐 id 字符串，清空=不受限，提交转 number|null）。 */
  packageId?: string;
  /** 父租户（REQ-20261122-102954；表单态为租户 id 字符串，清空=顶层，提交转 string|null）。 */
  parentId?: string;
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

  // 租户层级树（REQ-20261122-102954 契约⑨）：parent 下拉数据源；拉取失败/信封空降级为空数组
  // （下拉无选项，列表不受影响）；wire 归一=数组校验+节点 children 缺省兜底（旧口径/同形 mock 容忍）
  const [tenantTree, setTenantTree] = useState<AdminTenantTreeNode[]>([]);
  useEffect(() => {
    // page/size 仅随请求携带（统一列表分页口径，menuTree 同先例；后端全量返回不截断）
    systemApi.tenantTree(1, crud.size)
      .then((tree) => setTenantTree(Array.isArray(tree) ? tree : []))
      .catch(() => setTenantTree([]));
  }, []);

  const [editing, setEditing] = useState<AdminTenantView | null>(null);
  const [creating, setCreating] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<TenantFormValues>();

  // 用量 Modal（REQ-20261122-150802 契约⑧：操作列「用量」按需拉取 usage 端点，list 不带 usage）
  const [usageTarget, setUsageTarget] = useState<AdminTenantView | null>(null);
  const [usage, setUsage] = useState<TenantUsageView | null>(null);
  const [usageLoading, setUsageLoading] = useState(false);
  const openUsage = (record: AdminTenantView) => {
    setUsageTarget(record);
    setUsage(null);
    setUsageLoading(true);
    systemApi.tenantUsage(record.id)
      .then(setUsage)
      .catch((ex: unknown) => {
        message.error(ex instanceof ApiError ? ex.message : t('tenants.usage.loadFailed'));
        setUsageTarget(null);
      })
      .finally(() => setUsageLoading(false));
  };

  // 配额 Modal（REQ-20261122-150802 契约⑧：PUT quota 覆盖+清除继承；表单态 GiB，提交换算字节）
  const GIB_LABEL_SOURCE: Record<TenantQuotaSource, string> = {
    TENANT_OVERRIDE: t('tenants.usage.quotaSourceTenant'),
    PACKAGE: t('tenants.usage.quotaSourcePackage'),
    PLATFORM_DEFAULT: t('tenants.usage.quotaSourcePlatform'),
  };
  const [quotaTarget, setQuotaTarget] = useState<AdminTenantView | null>(null);
  const [quotaUsage, setQuotaUsage] = useState<TenantUsageView | null>(null);
  const [quotaSubmitting, setQuotaSubmitting] = useState(false);
  const [quotaForm] = Form.useForm<{ quotaGiB?: number }>();
  const openQuota = (record: AdminTenantView) => {
    setQuotaTarget(record);
    setQuotaUsage(null);
    quotaForm.resetFields();
    // 当前有效配额/来源展示 + 租户覆盖回显（仅覆盖态回填 GiB；继承态留空提示回落）
    systemApi.tenantUsage(record.id)
      .then((data) => {
        setQuotaUsage(data);
        if (data.quotaSource === 'TENANT_OVERRIDE' && data.quotaStorageBytes > 0) {
          quotaForm.setFieldsValue({ quotaGiB: data.quotaStorageBytes / BYTES_PER_GIB });
        }
      })
      .catch((ex: unknown) => {
        message.error(ex instanceof ApiError ? ex.message : t('tenants.quota.loadFailed'));
      });
  };
  const submitQuota = async () => {
    if (!quotaTarget) {
      return;
    }
    const { quotaGiB } = await quotaForm.validateFields();
    // 留空=清除覆盖（null 提交回落套餐默认/平台缺省）；0=不限（0 字节口径）
    const bytes = quotaGiB == null ? null : Math.round(quotaGiB * BYTES_PER_GIB);
    setQuotaSubmitting(true);
    try {
      await systemApi.tenantQuota(quotaTarget.id, bytes);
      message.success(t('tenants.quota.saved'));
      setQuotaTarget(null);
      crud.reload();
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : t('tenants.quota.loadFailed'));
    } finally {
      setQuotaSubmitting(false);
    }
  };

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
      graceUntil: record.graceUntil ? dayjs(record.graceUntil) : undefined,
      packageId: record.packageId ?? undefined,
      parentId: record.parentId ?? undefined,
    });
    setCreating(false);
  };

  // 防环排除（REQ-20261122-102954 契约③）：编辑时父租户候选剔除自身与全部后代（改父构成环由后端 TENANT_CYCLE 兜底拒绝）
  const excludedParentIds = useMemo(() => {
    const excluded = new Set<string>();
    if (!editing) {
      return excluded;
    }
    const walk = (nodes: AdminTenantTreeNode[]): boolean => {
      for (const node of nodes ?? []) {
        if (node.id === editing.id) {
          excluded.add(node.id);
          const collect = (list: AdminTenantTreeNode[]): void => {
            for (const child of list ?? []) {
              excluded.add(child.id);
              collect(child.children ?? []);
            }
          };
          collect(node.children ?? []);
          return true;
        }
        if (walk(node.children ?? [])) {
          return true;
        }
      }
      return false;
    };
    walk(tenantTree);
    return excluded;
  }, [editing, tenantTree]);

  const parentTreeData = useMemo<ParentTreeOption[]>(() => {
    const toOption = (node: AdminTenantTreeNode): ParentTreeOption => ({
      value: node.id,
      // 选项文案=租户名称（契约⑩未钉编码后缀；选中回显与防环断言按名称口径）
      title: node.tenantName,
      children: (node.children ?? []).filter((child) => !excludedParentIds.has(child.id)).map(toOption),
    });
    return tenantTree.filter((node) => !excludedParentIds.has(node.id)).map(toOption);
  }, [tenantTree, excludedParentIds]);

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
    // 父租户归一（REQ-20261122-102954）：清空=顶层（null）；提交 wire 字符串口径
    const parentId = values.parentId || null;
    try {
      if (editing) {
        await systemApi.tenantUpdate(editing.id, {
          tenantName: values.tenantName,
          remark: values.remark,
          ...profile,
          // 宽限期（REQ-20261122-150802 确认⑥）：update 收 graceUntil（Dayjs→ISO，清空=null 不启用）
          graceUntil: values.graceUntil ? values.graceUntil.toISOString() : null,
          packageId,
          parentId,
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
          parentId,
        });
        message.success(t('tenants.form.created'));
      }
      setEditing(null);
      setCreating(false);
      crud.reload(1);
    } catch (ex: unknown) {
      // 层级域错误码映射（REQ-20261122-102954 契约③）：TENANT_DEPTH_EXCEEDED/TENANT_CYCLE
      if (ex instanceof ApiError && ex.code === 'TENANT_DEPTH_EXCEEDED') {
        message.error(t('tenants.errors.depthExceeded'));
      } else if (ex instanceof ApiError && ex.code === 'TENANT_CYCLE') {
        message.error(t('tenants.errors.cycle'));
      } else {
        message.error(ex instanceof ApiError ? ex.message : t('common.errors.operationFailed'));
      }
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
      // 层级列（REQ-20261122-102954 契约⑨）：按 level 缩进（1=顶层，上限 3；缺失=旧口径按顶层兜底）
      title: t('tenants.columns.level'), dataIndex: 'level', key: 'level', minWidth: 110,
      render: (_, record) => {
        const level = Math.min(Math.max(record.level ?? 1, 1), TENANT_MAX_LEVEL);
        return (
          <span style={{ display: 'inline-block', paddingLeft: (level - 1) * 16 }}>
            {level === 1 ? t('tenants.columns.levelTop') : t('tenants.columns.levelN', { level })}
          </span>
        );
      },
    },
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
      // 生命周期四视觉态（REQ-20261122-150802 契约⑧）：status+expires/grace 派生合成，
      // 取色走 TONE_ANTD 预设名（UI-SPEC §2.2）；文案 t(`tenants.states.*`)
      title: t('tenants.columns.status'), dataIndex: 'status', key: 'status', minWidth: 110,
      render: (_, record) => {
        const state = deriveTenantVisualState(record, Date.now());
        return <Tag color={TENANT_STATE_ANTD[state]}>{t(`tenants.states.${state}`)}</Tag>;
      },
    },
    { title: t('tenants.columns.contact'), dataIndex: 'contactName', key: 'contactName', minWidth: 120, ellipsis: true, render: (v: string | null) => v ?? '—' },
    { title: t('tenants.columns.expiresAt'), dataIndex: 'expiresAt', key: 'expiresAt', minWidth: 180, render: (v: string | null) => formatDate(v) },
    // 宽限期截止（REQ-20261122-150802）：null=未启用宽限
    { title: t('tenants.columns.graceUntil'), dataIndex: 'graceUntil', key: 'graceUntil', minWidth: 180, render: (v: string | null | undefined) => (v ? formatDate(v) : '—') },
    { title: t('tenants.columns.remark'), dataIndex: 'remark', key: 'remark', minWidth: 200, ellipsis: true, render: (v: string | null) => v ?? '—' },
    { title: t('tenants.columns.createdAt'), dataIndex: 'createdAt', key: 'createdAt', minWidth: 180, render: (v: string) => formatDate(v) },
    {
      title: t('tenants.columns.actions'), key: 'actions', minWidth: 260, fixed: 'right',
      render: (_, record) => (
        <Space size={4}>
          <Auth code="system:tenant:update">
            <Button size="small" onClick={() => openEdit(record)}>{t('common.actions.edit')}</Button>
          </Auth>
          {/* 用量（REQ-20261122-150802 契约⑧：usage 端点按需拉取，权限挂 system:tenant:list 读面） */}
          <Auth code="system:tenant:list">
            <Button size="small" onClick={() => openUsage(record)}>{t('tenants.actions.usage')}</Button>
          </Auth>
          {/* 配额（REQ-20261122-150802 契约⑧：PUT quota 覆盖/清除继承，权限=更新面） */}
          <Auth code="system:tenant:update">
            <Button size="small" onClick={() => openQuota(record)}>{t('tenants.actions.quota')}</Button>
          </Auth>
          <Auth code="system:tenant:update">
            <Popconfirm
              title={t('tenants.status.toggleConfirm', { action: record.status === 'ENABLED' ? t('common.status.disabled') : t('common.status.enabled'), code: record.tenantCode })}
              description={record.status === 'ENABLED' ? t('tenants.status.cascadeHint') : undefined}
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
        {/* 父租户（REQ-20261122-102954）：树形下拉（GET /tenants/tree 数据源，展示名（编码））；
            编辑时选项已剔除自身与后代（防环）；可清空=顶层；提交 parentId string|null（TENANT_DEPTH_EXCEEDED/TENANT_CYCLE 后端校验） */}
        <Form.Item name="parentId" label={t('tenants.form.parent')} extra={t('tenants.form.parentExtra')}>
          <TreeSelect
            allowClear
            placeholder={t('tenants.form.parentPlaceholder')}
            treeData={parentTreeData}
            treeNodeFilterProp="title"
            treeDefaultExpandAll
            showSearch
          />
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
        {/* 宽限期截止（REQ-20261122-150802 确认⑥⑧）：update 收 graceUntil（创建载荷不含，仅编辑态展示）；清空=不启用宽限 */}
        {editing ? (
          <Form.Item name="graceUntil" label={t('tenants.form.graceUntil')} extra={t('tenants.form.graceUntilExtra')}>
            <DatePicker style={{ width: '100%' }} allowClear placeholder={t('common.validation.optional')} />
          </Form.Item>
        ) : null}
        {editing ? (
          <Text type="secondary" style={{ fontSize: 12 }}>
            {t('common.validation.optimisticLock', { version: editing.version })}{t('common.validation.optimisticLockConflict')}
          </Text>
        ) : null}
      </CrudFormModal>

      {/* 用量 Modal（REQ-20261122-150802 契约⑧）：storageBytes 人类可读/userCount/有效配额值/来源层级标注 */}
      <Modal
        open={usageTarget !== null}
        title={usageTarget ? t('tenants.usage.title', { code: usageTarget.tenantCode }) : ''}
        footer={[
          <Button key="close" onClick={() => setUsageTarget(null)}>{t('tenants.usage.close')}</Button>,
        ]}
        onCancel={() => setUsageTarget(null)}
        width={520}
      >
        {usage ? (
          <Descriptions column={1} size="small" bordered>
            <Descriptions.Item label={t('tenants.usage.storageUsed')}>{formatBytes(usage.storageBytes)}</Descriptions.Item>
            <Descriptions.Item label={t('tenants.usage.userCount')}>{usage.userCount}</Descriptions.Item>
            <Descriptions.Item label={t('tenants.usage.quota')}>
              {usage.quotaStorageBytes === 0 ? t('tenants.usage.unlimited') : formatBytes(usage.quotaStorageBytes)}
            </Descriptions.Item>
            <Descriptions.Item label={t('tenants.usage.quotaSource')}>{GIB_LABEL_SOURCE[usage.quotaSource]}</Descriptions.Item>
          </Descriptions>
        ) : (
          <div style={{ textAlign: 'center', padding: '24px 0' }}>{usageLoading ? <Spin /> : null}</div>
        )}
      </Modal>

      {/* 配额 Modal（REQ-20261122-150802 契约⑧）：PUT 覆盖（GiB↔字节换算）+ 留空=清除继承回落 */}
      <Modal
        open={quotaTarget !== null}
        title={quotaTarget ? t('tenants.quota.title', { code: quotaTarget.tenantCode }) : ''}
        confirmLoading={quotaSubmitting}
        onOk={() => void submitQuota()}
        onCancel={() => setQuotaTarget(null)}
        width={520}
      >
        {quotaUsage ? (
          <Text type="secondary" style={{ display: 'block', marginBottom: 12 }}>
            {t('tenants.quota.current')}：
            {quotaUsage.quotaStorageBytes === 0 ? t('tenants.usage.unlimited') : formatBytes(quotaUsage.quotaStorageBytes)}
            {' · '}{t('tenants.quota.currentSource')}：{GIB_LABEL_SOURCE[quotaUsage.quotaSource]}
            {quotaUsage.quotaSource !== 'TENANT_OVERRIDE'
              ? ` · ${t('tenants.quota.inheritHint', { source: GIB_LABEL_SOURCE[quotaUsage.quotaSource] })}`
              : ''}
          </Text>
        ) : null}
        <Form form={quotaForm} layout="vertical">
          <Form.Item name="quotaGiB" label={t('tenants.quota.override')} extra={t('tenants.quota.quotaExtra')}>
            <InputNumber
              min={0}
              precision={2}
              style={{ width: '100%' }}
              placeholder={t('tenants.quota.overridePlaceholder')}
              addonAfter="GiB"
            />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
