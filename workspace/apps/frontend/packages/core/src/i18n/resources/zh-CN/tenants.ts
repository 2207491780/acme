/** zh-CN 租户管理资源包（p2-admin）。 */
export const tenants = {
  title: '租户管理',
  search: { placeholder: '搜索租户代码 / 名称' },
  reload: '刷新租户清单',
  create: '新建租户',
  total: '共 {{total}} 个租户',
  columns: {
    tenantCode: '租户代码',
    tenantName: '租户名称',
    status: '状态',
    remark: '备注',
    createdAt: '创建时间',
    actions: '操作',
  },
  form: {
    editTitle: '编辑租户：{{code}}',
    createTitle: '新建租户',
    code: '租户代码',
    codeRequired: '请输入租户代码',
    codePlaceholder: '如 acme',
    codeImmutable: '租户代码不可修改',
    codeExtra: '创建后不可修改（登录与数据隔离标识）',
    name: '租户名称',
    nameRequired: '请输入租户名称',
    namePlaceholder: '租户显示名称',
    remark: '备注',
    created: '租户已创建',
    updated: '租户已更新',
  },
  status: {
    toggleConfirm: '确认{{action}}租户 {{code}}？',
    enabled: '租户已启用',
    disabled: '租户已停用',
  },
  errors: {
    loadFailed: '租户清单加载失败',
  },
};
