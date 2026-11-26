import { useCallback, useEffect, useMemo, useState } from 'react';
import { ReloadOutlined } from '@ant-design/icons';
import {
  Alert, App, Button, Card, Col, DatePicker, Descriptions, Empty, Form, Input, InputNumber,
  Modal, Popconfirm, Row, Select, Space, Spin, Table, Tabs, Tag, TreeSelect, Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs, { type Dayjs } from 'dayjs';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  ApiError, Auth, authApi, formatDate, systemApi, TONE_ANTD, TONES,
  tenantPackageApi, tenantTemplateApi, tenantUsageOverview, useAuthStore, useCrud, useMeStore,
  useSystemConfigStore,
  type AdminTenantInitTemplateView, type AdminTenantPackageView, type AdminTenantTreeNode,
  type AdminTenantView, type TenantQuotaSource, type TenantTemplateSavePayload,
  type TenantUsageOverviewRow, type TenantUsageOverviewView, type TenantUsageView,
} from '@acme/core';
import { Chart } from '../components/Chart';
import { CrudFormModal } from '../components/CrudFormModal';
import { CrudTable } from '../components/CrudTable';
import { useDashboardCharts, type DashboardChartStats } from '../hooks/useDashboardCharts';

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
  /** 开通模板（REQ-20261122-233426 确认⑦：可选 Select，表单态为模板 code，清空=动态授权集现状）。 */
  templateCode?: string;
  /** 初始管理员（选中模板时必填——init 端点要求；adminNickname 可空=后端按租户名兜底）。 */
  adminUsername?: string;
  adminNickname?: string;
}

/** 模板表单值（模板管理 Tab；rolePresets/configEntries 结构化编辑，REQ-20261122-233426 确认⑦）。 */
interface TemplateFormValues {
  code: string;
  name: string;
  remark?: string;
  /** 默认套餐（表单态为套餐 id 字符串，清空=不设置，提交转 number|null）。 */
  defaultPackageId?: string;
  adminUsernamePrefix?: string;
  adminNickname?: string;
  rolePresets: Array<{ roleKey: string; roleName: string; permissionCodes?: string[] }>;
  configEntries: Array<{ configKey: string; value?: string }>;
}

// ————— Tab 1：租户列表（现状迁移自原 TenantsPage；REQ-20261122-233426 确认⑦） —————

/** 租户列表 Tab：分页/搜索/新建/改名/启停；无删除能力——停用代替（FR-007）。
 *  迁移 CRUD 范本（REQ-20261114-120101）：headless 状态机走 @acme/core useCrud，
 *  展示层为 CrudTable + CrudFormModal；动作按钮按 me.permissions 权限码显隐（fail-closed）。
 *  REQ-20261122-233426 确认③⑦：新建表单增可选「开通模板」Select——选中模板时提交走
 *  init 端点（携带 templateCode+初始管理员），未选=tenantCreate 现行为逐字不变。 */
function TenantListTab() {
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
      .then((result) => setPackages(result?.records ?? []))
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

  // 开通模板目录（REQ-20261122-233426 确认⑦）：新建表单模板 Select 选项源；拉取失败静默降级（无选项）
  const [templates, setTemplates] = useState<AdminTenantInitTemplateView[]>([]);
  useEffect(() => {
    tenantTemplateApi.page({ page: 1, size: 100 })
      .then((result) => setTemplates(result?.records ?? []))
      .catch(() => setTemplates([]));
  }, []);

  const [editing, setEditing] = useState<AdminTenantView | null>(null);
  const [creating, setCreating] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<TenantFormValues>();
  // 选中模板 → 表单追加初始管理员字段（init 端点要求 adminUsername；确认③）
  const templateCode = Form.useWatch('templateCode', form);

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
      } else if (values.templateCode) {
        // 模板开通路径（REQ-20261122-233426 确认③）：init 端点携带 templateCode+初始管理员——
        // 命中模板=按模板预置角色/授权/配置/默认套餐（adminUsername 前缀合成由后端完成）
        await systemApi.tenantInit({
          tenantCode: values.tenantCode,
          tenantName: values.tenantName,
          remark: values.remark,
          ...profile,
          packageId,
          parentId,
          templateCode: values.templateCode,
          adminUsername: (values.adminUsername ?? '').trim(),
          adminNickname: values.adminNickname?.trim() || undefined,
        });
        message.success(t('tenants.form.created'));
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
        {/* 开通模板（REQ-20261122-233426 确认③⑦，仅创建态）：可选 Select（选项=模板列表）；
            选中=提交走 init 端点（携带 templateCode+初始管理员），清空=tenantCreate 现行为逐字不变 */}
        {!editing ? (
          <Form.Item name="templateCode" label={t('tenants.form.template')} extra={t('tenants.form.templateExtra')}>
            <Select
              allowClear
              placeholder={t('tenants.form.templatePlaceholder')}
              optionFilterProp="label"
              showSearch
              options={templates.map((tpl) => ({ value: tpl.code, label: tpl.name }))}
            />
          </Form.Item>
        ) : null}
        {/* 初始管理员（REQ-20261122-233426 确认③）：选中模板时必填（init 端点要求 adminUsername）；昵称可空 */}
        {!editing && templateCode ? (
          <>
            <Form.Item
              name="adminUsername"
              label={t('tenants.form.adminUsername')}
              rules={[{ required: true, message: t('tenants.form.adminUsernameRequired') }]}
            >
              <Input placeholder={t('tenants.form.adminUsernamePlaceholder')} autoComplete="off" />
            </Form.Item>
            <Form.Item name="adminNickname" label={t('tenants.form.adminNickname')}>
              <Input placeholder={t('tenants.form.adminNicknamePlaceholder')} autoComplete="off" />
            </Form.Item>
          </>
        ) : null}
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

// ————— Tab 2：模板管理（REQ-20261122-233426 确认②④⑦：模板 CRUD+content 结构化编辑） —————

/** 模板管理 Tab：CrudTable 风格模板列表 + content 结构化编辑表单
 *  （rolePresets 数组编辑=Form.List 嵌套 Select tags 权限点输入；configEntries 键值对编辑）。
 *  权限点=system:tenant-template:list/create/update/delete（V32 种子，确认⑤）；fail-closed 显隐。 */
function TenantTemplatesTab() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const permissions = useMeStore((s) => s.me?.permissions) ?? [];

  const crud = useCrud<AdminTenantInitTemplateView>({
    fetchPage: ({ page, size, keyword, signal }) =>
      tenantTemplateApi.page({ page, size, keyword: keyword || undefined }, signal),
    permissions,
    pageSize: 10,
    errorMessage: t('tenants.templates.loadFailed'),
  });

  // 套餐目录（默认套餐下拉选项源；失败静默降级为空选项）
  const [packages, setPackages] = useState<AdminTenantPackageView[]>([]);
  useEffect(() => {
    tenantPackageApi.page({ page: 1, size: 100 })
      .then((result) => setPackages(result?.records ?? []))
      .catch(() => setPackages([]));
  }, []);

  const [editing, setEditing] = useState<AdminTenantInitTemplateView | null>(null);
  const [creating, setCreating] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<TemplateFormValues>();

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    setCreating(true);
  };

  // 编辑回显：列表行自带完整 content（确认④无 detail 端点——列表即全量）
  const openEdit = (record: AdminTenantInitTemplateView) => {
    setEditing(record);
    form.setFieldsValue({
      code: record.code,
      name: record.name,
      remark: record.remark ?? undefined,
      defaultPackageId: record.defaultPackageId ?? undefined,
      adminUsernamePrefix: record.content.adminUsernamePrefix,
      adminNickname: record.content.adminNickname,
      rolePresets: record.content.rolePresets.map((preset) => ({
        roleKey: preset.roleKey,
        roleName: preset.roleName,
        permissionCodes: [...preset.permissionCodes],
      })),
      configEntries: record.content.configEntries.map((entry) => ({
        configKey: entry.configKey,
        value: entry.value,
      })),
    });
    setCreating(false);
  };

  const submit = async () => {
    const values = await form.validateFields();
    // 空角色集保存即拒（确认③口径与后端同语义：至少预置一个角色）
    if (!values.rolePresets || values.rolePresets.length === 0) {
      message.error(t('tenants.templates.roles.emptyRoles'));
      return;
    }
    setSubmitting(true);
    const payload: TenantTemplateSavePayload = {
      code: values.code,
      name: values.name,
      remark: values.remark?.trim() ? values.remark : null,
      content: {
        adminUsernamePrefix: values.adminUsernamePrefix?.trim() || undefined,
        adminNickname: values.adminNickname?.trim() || undefined,
        rolePresets: (values.rolePresets ?? []).map((preset) => ({
          roleKey: preset.roleKey,
          roleName: preset.roleName,
          permissionCodes: preset.permissionCodes ?? [],
        })),
        configEntries: (values.configEntries ?? []).map((entry) => ({
          configKey: entry.configKey,
          value: entry.value ?? '',
        })),
      },
      // 默认套餐归一：清空=不设置（null）；提交转 number 口径（与 tenantCreate.packageId 同型）
      defaultPackageId: values.defaultPackageId ? Number(values.defaultPackageId) : null,
    };
    try {
      if (editing) {
        await tenantTemplateApi.update(editing.id, payload);
      } else {
        await tenantTemplateApi.create(payload);
      }
      message.success(t('tenants.templates.form.saved'));
      setEditing(null);
      setCreating(false);
      crud.reload();
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : t('common.errors.operationFailed'));
    } finally {
      setSubmitting(false);
    }
  };

  const removeTemplate = async (record: AdminTenantInitTemplateView) => {
    try {
      await tenantTemplateApi.remove(record.id);
      message.success(t('tenants.templates.deleted'));
      crud.reload();
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : t('common.errors.operationFailed'));
    }
  };

  const columns: ColumnsType<AdminTenantInitTemplateView> = [
    { title: t('tenants.templates.columns.code'), dataIndex: 'code', key: 'code', minWidth: 150, fixed: 'left' },
    { title: t('tenants.templates.columns.name'), dataIndex: 'name', key: 'name', minWidth: 160 },
    {
      // 预置角色列：roleName 汇总（ellipsis 截断；roleKey 为后端建角色编码）
      title: t('tenants.templates.columns.rolePresets'), key: 'rolePresets', minWidth: 220, ellipsis: true,
      render: (_, record) => record.content.rolePresets.map((preset) => preset.roleName).join(' / ') || '—',
    },
    {
      title: t('tenants.templates.columns.configEntries'), key: 'configEntries', minWidth: 110,
      render: (_, record) => record.content.configEntries.length,
    },
    {
      // 默认套餐列：套餐目录映射（未设置=未设置）
      title: t('tenants.templates.columns.defaultPackage'), key: 'defaultPackage', minWidth: 140, ellipsis: true,
      render: (_, record) => {
        if (!record.defaultPackageId) {
          return t('tenants.templates.columns.none');
        }
        return packages.find((pkg) => pkg.id === record.defaultPackageId)?.packageName ?? record.defaultPackageId;
      },
    },
    { title: t('tenants.templates.columns.remark'), dataIndex: 'remark', key: 'remark', minWidth: 200, ellipsis: true, render: (v: string | null) => v ?? '—' },
    { title: t('tenants.templates.columns.createdAt'), dataIndex: 'createdAt', key: 'createdAt', minWidth: 180, render: (v: string) => formatDate(v) },
    {
      title: t('tenants.templates.columns.actions'), key: 'actions', minWidth: 150, fixed: 'right',
      render: (_, record) => (
        <Space size={4}>
          <Auth code="system:tenant-template:update">
            <Button size="small" onClick={() => openEdit(record)}>{t('common.actions.edit')}</Button>
          </Auth>
          <Auth code="system:tenant-template:delete">
            <Popconfirm
              title={t('tenants.templates.deleteConfirm', { code: record.code })}
              onConfirm={() => void removeTemplate(record)}
            >
              <Button size="small" danger>{t('common.actions.delete')}</Button>
            </Popconfirm>
          </Auth>
        </Space>
      ),
    },
  ];

  return (
    <>
      <CrudTable<AdminTenantInitTemplateView>
        crud={crud}
        tableKey="tenant-init-templates"
        title={t('tenants.templates.title')}
        columns={columns}
        searchPlaceholder={t('tenants.templates.search.placeholder')}
        reloadAriaLabel={t('tenants.templates.reload')}
        createPermission="system:tenant-template:create"
        createLabel={t('tenants.templates.create')}
        onCreate={openCreate}
        totalText={(total) => t('tenants.templates.total', { total })}
      />

      <CrudFormModal<TemplateFormValues>
        open={creating || editing !== null}
        title={editing ? t('tenants.templates.form.editTitle', { code: editing.code }) : t('tenants.templates.form.createTitle')}
        form={form}
        submitting={submitting}
        width={680}
        onOk={() => void submit()}
        onCancel={() => { setCreating(false); setEditing(null); }}
      >
        <Form.Item
          name="code"
          label={t('tenants.templates.form.code')}
          rules={[
            { required: true, message: t('tenants.templates.form.codeRequired') },
            { pattern: CODE_PATTERN, message: t('common.validation.codePattern') },
            { min: 2, max: 64, message: t('common.validation.lengthRange', { min: 2, max: 64 }) },
          ]}
          extra={editing ? t('tenants.templates.form.codeImmutable') : undefined}
        >
          <Input placeholder={t('tenants.templates.form.codePlaceholder')} disabled={editing !== null} autoComplete="off" />
        </Form.Item>
        <Form.Item
          name="name"
          label={t('tenants.templates.form.name')}
          rules={[
            { required: true, message: t('tenants.templates.form.nameRequired') },
            { min: 1, max: 128, message: t('common.validation.lengthRange', { min: 1, max: 128 }) },
          ]}
        >
          <Input placeholder={t('tenants.templates.form.namePlaceholder')} />
        </Form.Item>
        <Form.Item name="remark" label={t('tenants.templates.form.remark')} rules={[{ max: 255, message: t('common.validation.maxLength', { max: 255 }) }]}>
          <Input.TextArea placeholder={t('common.validation.optional')} rows={2} />
        </Form.Item>
        {/* 默认套餐（可清空=不设置；命中模板开通且未显式指定套餐时绑定） */}
        <Form.Item name="defaultPackageId" label={t('tenants.templates.form.defaultPackage')} extra={t('tenants.templates.form.defaultPackageExtra')}>
          <Select
            allowClear
            placeholder={t('tenants.templates.form.defaultPackagePlaceholder')}
            optionFilterProp="label"
            showSearch
            options={packages.map((pkg) => ({ value: pkg.id, label: pkg.packageName }))}
          />
        </Form.Item>
        <Row gutter={12}>
          <Col xs={24} sm={12}>
            <Form.Item name="adminUsernamePrefix" label={t('tenants.templates.form.adminUsernamePrefix')}>
              <Input placeholder={t('tenants.templates.form.adminUsernamePrefixPlaceholder')} autoComplete="off" />
            </Form.Item>
          </Col>
          <Col xs={24} sm={12}>
            <Form.Item name="adminNickname" label={t('tenants.templates.form.adminNickname')}>
              <Input placeholder={t('tenants.templates.form.adminNicknamePlaceholder')} autoComplete="off" />
            </Form.Item>
          </Col>
        </Row>
        {/* rolePresets 结构化编辑（确认②）：roleKey/roleName 必填 + permissionCodes 标签输入
            （Select mode=tags 自由输入，保留前缀由后端保存时校验拒绝——PARAM_INVALID） */}
        <Form.Item label={t('tenants.templates.roles.title')} style={{ marginBottom: 4 }}>
          <Form.List name="rolePresets">
            {(fields, { add, remove }) => (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                {fields.map((field) => (
                  <Card key={field.key} size="small" title={`#${field.name + 1}`}>
                    <Row gutter={12}>
                      <Col xs={24} sm={8}>
                        <Form.Item
                          name={[field.name, 'roleKey']}
                          label={t('tenants.templates.roles.roleKey')}
                          rules={[
                            { required: true, message: t('tenants.templates.roles.roleKeyRequired') },
                            { pattern: CODE_PATTERN, message: t('tenants.templates.roles.roleKeyPattern') },
                          ]}
                        >
                          <Input autoComplete="off" />
                        </Form.Item>
                      </Col>
                      <Col xs={24} sm={8}>
                        <Form.Item
                          name={[field.name, 'roleName']}
                          label={t('tenants.templates.roles.roleName')}
                          rules={[{ required: true, message: t('tenants.templates.roles.roleNameRequired') }]}
                        >
                          <Input autoComplete="off" />
                        </Form.Item>
                      </Col>
                      <Col xs={24} sm={8} style={{ display: 'flex', alignItems: 'flex-end', paddingBottom: 24 }}>
                        <Button
                          size="small"
                          danger
                          block
                          aria-label={t('tenants.templates.roles.remove')}
                          onClick={() => remove(field.name)}
                        >
                          {t('tenants.templates.roles.remove')}
                        </Button>
                      </Col>
                    </Row>
                    <Form.Item
                      name={[field.name, 'permissionCodes']}
                      label={t('tenants.templates.roles.permissionCodes')}
                      style={{ marginBottom: 0 }}
                    >
                      <Select
                        mode="tags"
                        open={false}
                        tokenSeparators={[',']}
                        placeholder={t('tenants.templates.roles.permissionPlaceholder')}
                        aria-label={t('tenants.templates.roles.permissionCodes')}
                      />
                    </Form.Item>
                  </Card>
                ))}
                <Button
                  size="small"
                  type="dashed"
                  onClick={() => add({ roleKey: '', roleName: '', permissionCodes: [] })}
                  aria-label={t('tenants.templates.roles.add')}
                >
                  {t('tenants.templates.roles.add')}
                </Button>
              </div>
            )}
          </Form.List>
        </Form.Item>
        {/* configEntries 结构化编辑（确认②）：键值对写入 sys_config（租户域）；键必填 */}
        <Form.Item label={t('tenants.templates.configs.title')} style={{ marginBottom: 4 }}>
          <Form.List name="configEntries">
            {(fields, { add, remove }) => (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {fields.map((field) => (
                  <Row key={field.key} gutter={8} align="top">
                    <Col flex="1 1 180px">
                      <Form.Item
                        name={[field.name, 'configKey']}
                        rules={[
                          { required: true, message: t('tenants.templates.configs.configKeyRequired') },
                          { pattern: /^[a-z][a-z0-9._-]*$/i, message: t('tenants.templates.configs.configKeyPattern') },
                        ]}
                      >
                        <Input placeholder={t('tenants.templates.configs.configKey')} autoComplete="off" />
                      </Form.Item>
                    </Col>
                    <Col flex="1 1 180px">
                      <Form.Item name={[field.name, 'value']}>
                        <Input placeholder={t('tenants.templates.configs.value')} autoComplete="off" />
                      </Form.Item>
                    </Col>
                    <Col flex="none">
                      <Button
                        size="small"
                        danger
                        aria-label={t('tenants.templates.configs.remove')}
                        onClick={() => remove(field.name)}
                      >
                        {t('tenants.templates.configs.remove')}
                      </Button>
                    </Col>
                  </Row>
                ))}
                <Button
                  size="small"
                  type="dashed"
                  onClick={() => add({ configKey: '', value: '' })}
                  aria-label={t('tenants.templates.configs.add')}
                >
                  {t('tenants.templates.configs.add')}
                </Button>
              </div>
            )}
          </Form.List>
        </Form.Item>
      </CrudFormModal>
    </>
  );
}

// ————— Tab 3：运营概览（REQ-20261122-233426 确认⑥⑦：四态统计卡+用量 TopN+分布图+切换视角） —————

/** 运营概览四态卡顺序与取色（语义色走 TONES 令牌，UI-SPEC §2.2——禁硬编码 hex）。 */
const OVERVIEW_CARDS = [
  { key: 'enabled', tone: TONES.success },
  { key: 'grace', tone: TONES.warning },
  { key: 'expired', tone: TONES.danger },
  { key: 'disabled', tone: TONES.muted },
] as const;

/** 运营概览 Tab：GET /tenants/usage-overview（权限 system:tenant:usage-overview，fail-closed）
 *  四态统计卡（statusCounts）+ 用量 TopN 表格（tenantUsages 行字段=契约⑥原文）+
 *  echarts 分布图（复用 Chart.tsx 懒加载惯例，优先套餐分布、缺省回退四态分布）+
 *  行操作「切换视角」（复用既有 tenant-switch 调用面：签发目标租户新令牌整体替换会话）。 */
function TenantOverviewTab() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const me = useMeStore((s) => s.me);
  const isSuperAdmin = me?.roles.includes('super_admin') ?? false;
  // 图表主题（useDashboardCharts 既有管线内部消费 settings→themeSnapshot，主题切换整体刷新）

  const canOverview = (me?.permissions ?? []).includes('system:tenant:usage-overview');
  const [overview, setOverview] = useState<TenantUsageOverviewView | null>(null);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);
  const [switchingId, setSwitchingId] = useState<string | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setFailed(false);
    tenantUsageOverview()
      .then((data) => setOverview(data && typeof data === 'object' ? data : null))
      .catch(() => setFailed(true))
      .finally(() => setLoading(false));
  }, []);
  useEffect(() => {
    // 无权限不发请求（fail-closed：不制造 403 噪音）
    if (canOverview) {
      load();
    }
  }, [canOverview, load]);

  // 分布图数据源（确认⑦ echarts 分布图）：优先套餐分布（packageDistribution），缺省回退四态分布；
  // 经 useDashboardCharts stats 预留位接入（createChartOption+themeSnapshot 既有管线，memo 稳定）；
  // failed 显式传 null（空态）；overview 未就绪传 undefined（hook 同样按空态处理，无本地派生数据源）
  const chartStats = useMemo<DashboardChartStats | null | undefined>(() => {
    if (failed) {
      return null;
    }
    if (!overview) {
      return undefined;
    }
    const dist = overview.packageDistribution && overview.packageDistribution.length > 0
      ? overview.packageDistribution.map((item) => ({
        name: item.packageName ?? t('tenants.overview.unbound'),
        value: item.tenantCount,
      }))
      : OVERVIEW_CARDS.map((card) => ({
        name: t(`tenants.overview.cards.${card.key}`),
        value: overview.statusCounts[card.key],
      }));
    return { distribution: { items: dist } };
  }, [failed, overview, t]);
  const charts = useDashboardCharts(chartStats);

  // 切换视角（确认⑦：复用 tenant-switch 调用面）——仅超管可见（与顶栏切换租户同源判定）；
  // 签发目标租户新令牌并整体替换会话，新租户画像/配置随新会话重载，成功后回概览
  const switchPerspective = async (row: TenantUsageOverviewRow) => {
    if (switchingId) {
      return;
    }
    setSwitchingId(row.tenantId);
    try {
      const issued = await authApi.tenantSwitch(row.tenantCode);
      useAuthStore.getState().setSession({
        accessToken: issued.accessToken,
        expiresAtMs: Date.parse(issued.expiresAt),
        userId: issued.userId,
        username: issued.username,
        nickname: issued.nickname,
        tenantCode: row.tenantCode,
      }, useAuthStore.getState().remember);
      void useMeStore.getState().load();
      void useSystemConfigStore.getState().load();
      message.success(t('tenants.overview.switchSuccess', { tenant: row.tenantCode }));
      navigate('/');
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : t('tenants.overview.switchFailed'));
    } finally {
      setSwitchingId(null);
    }
  };

  if (!canOverview) {
    return (
      <Empty
        image={Empty.PRESENTED_IMAGE_SIMPLE}
        description={t('tenants.overview.noPermission')}
        style={{ padding: '48px 0' }}
      />
    );
  }

  const quotaSourceLabel: Record<TenantQuotaSource, string> = {
    TENANT_OVERRIDE: t('tenants.usage.quotaSourceTenant'),
    PACKAGE: t('tenants.usage.quotaSourcePackage'),
    PLATFORM_DEFAULT: t('tenants.usage.quotaSourcePlatform'),
  };

  const usageColumns: ColumnsType<TenantUsageOverviewRow> = [
    { title: t('tenants.overview.columns.tenantCode'), dataIndex: 'tenantCode', key: 'tenantCode', minWidth: 140, fixed: 'left' },
    { title: t('tenants.overview.columns.tenantName'), dataIndex: 'tenantName', key: 'tenantName', minWidth: 160 },
    {
      title: t('tenants.overview.columns.status'), dataIndex: 'status', key: 'status', minWidth: 100,
      render: (v: TenantUsageOverviewRow['status']) => (
        <Tag color={v === 'ENABLED' ? TONE_ANTD.success : TONE_ANTD.muted}>
          {v === 'ENABLED' ? t('common.status.enabled') : t('common.status.disabled')}
        </Tag>
      ),
    },
    { title: t('tenants.overview.columns.storageBytes'), dataIndex: 'storageBytes', key: 'storageBytes', minWidth: 130, render: (v: number) => formatBytes(v) },
    // 有效配额：0=不限（与用量 Modal 同一口径文案）
    {
      title: t('tenants.overview.columns.quotaBytes'), dataIndex: 'quotaBytes', key: 'quotaBytes', minWidth: 130,
      render: (v: number) => (v === 0 ? t('tenants.usage.unlimited') : formatBytes(v)),
    },
    { title: t('tenants.overview.columns.quotaSource'), dataIndex: 'quotaSource', key: 'quotaSource', minWidth: 120, render: (v: TenantQuotaSource) => quotaSourceLabel[v] },
    { title: t('tenants.overview.columns.userCount'), dataIndex: 'userCount', key: 'userCount', minWidth: 100 },
    {
      title: t('tenants.overview.columns.actions'), key: 'actions', minWidth: 120, fixed: 'right',
      // 切换视角（确认⑦）：行操作入口复用 tenant-switch；仅超管可见（fail-closed）
      render: (_, record) => (isSuperAdmin ? (
        <Popconfirm
          title={t('tenants.overview.switchConfirm', { code: record.tenantCode })}
          onConfirm={() => void switchPerspective(record)}
        >
          <Button size="small" loading={switchingId === record.tenantId}>
            {t('tenants.overview.switchTo')}
          </Button>
        </Popconfirm>
      ) : null),
    },
  ];

  return (
    <Row gutter={[18, 18]}>
      <Col xs={24} xl={9}>
        <Card
          title={t('tenants.overview.title')}
          extra={(
            <Button
              icon={<ReloadOutlined />}
              loading={loading}
              onClick={load}
              aria-label={t('tenants.overview.reloadAria')}
            />
          )}
        >
          {/* 四态统计卡（确认⑥ statusCounts：enabled/grace/expired/disabled——口径同页面四视觉态） */}
          <Row gutter={[12, 12]}>
            {OVERVIEW_CARDS.map((card) => (
              <Col xs={12} md={6} xl={12} key={card.key}>
                <div className="overview-stat">
                  <div className="n" style={{ color: card.tone }}>
                    {overview ? overview.statusCounts[card.key] : '—'}
                  </div>
                  <div className="l">{t(`tenants.overview.cards.${card.key}`)}</div>
                </div>
              </Col>
            ))}
          </Row>
          {/* 分布图（确认⑦ echarts 分布图，复用 Chart.tsx 懒加载惯例；优先套餐分布回退四态分布） */}
          <Text type="secondary" style={{ display: 'block', marginTop: 16, marginBottom: 8, fontSize: 12 }}>
            {t('tenants.overview.statusChart')}
          </Text>
          <Chart option={charts.distribution ?? undefined} loading={loading || (!overview && !failed)} />
          {failed ? (
            <Alert
              type="error"
              showIcon
              style={{ marginTop: 12 }}
              message={t('tenants.overview.loadFailed')}
              action={<Button size="small" onClick={load}>{t('tenants.overview.retry')}</Button>}
            />
          ) : null}
        </Card>
      </Col>
      <Col xs={24} xl={15}>
        <Card
          title={t('tenants.overview.usageTable')}
          extra={overview ? (
            <Text type="secondary" style={{ fontSize: 12 }}>
              {t('tenants.overview.totalTenants')}：{overview.totalTenants}
            </Text>
          ) : undefined}
        >
          <Table
            rowKey="tenantId"
            columns={usageColumns}
            dataSource={overview?.tenantUsages ?? []}
            loading={loading}
            size="small"
            scroll={{ x: 'max-content' }}
            pagination={false}
            locale={{ emptyText: failed ? t('tenants.overview.loadFailed') : undefined }}
          />
        </Card>
      </Col>
    </Row>
  );
}

/**
 * 租户管理（p2-admin，REQ-20261122-233426 确认⑦三 Tab 化）：
 * 租户列表（现状迁入）+ 模板管理（开通模板 CRUD）+ 运营概览（平台用量视图）。
 * destroyOnHidden：切 Tab 即卸载非活动 pane（JobsPage/WorkflowPage 同先例，避免跨 Tab 文本歧义）。
 */
export function TenantsPage() {
  const { t } = useTranslation();
  const tabItems = useMemo(
    () => [
      { key: 'tenants', label: t('tenants.tabs.list'), children: <TenantListTab /> },
      { key: 'templates', label: t('tenants.tabs.templates'), children: <TenantTemplatesTab /> },
      { key: 'overview', label: t('tenants.tabs.overview'), children: <TenantOverviewTab /> },
    ],
    [t],
  );

  return <Tabs defaultActiveKey="tenants" items={tabItems} destroyOnHidden />;
}
