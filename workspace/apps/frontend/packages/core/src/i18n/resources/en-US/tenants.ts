/** en-US tenants resource pack (p2-admin). */
export const tenants = {
  title: 'Tenants',
  search: { placeholder: 'Search tenant code / name' },
  reload: 'Refresh tenants',
  create: 'New tenant',
  total: '{{total}} tenants in total',
  columns: {
    tenantCode: 'Tenant code',
    tenantName: 'Tenant name',
    status: 'Status',
    remark: 'Remark',
    createdAt: 'Created',
    actions: 'Actions',
  },
  form: {
    editTitle: 'Edit tenant: {{code}}',
    createTitle: 'New tenant',
    code: 'Tenant code',
    codeRequired: 'Please enter the tenant code',
    codePlaceholder: 'e.g. acme',
    codeImmutable: 'Tenant code cannot be changed',
    codeExtra: 'Cannot be changed after creation (identity for sign-in and data isolation)',
    name: 'Tenant name',
    nameRequired: 'Please enter the tenant name',
    namePlaceholder: 'Tenant display name',
    remark: 'Remark',
    created: 'Tenant created',
    updated: 'Tenant updated',
  },
  status: {
    toggleConfirm: 'Confirm {{action}} of tenant {{code}}?',
    enabled: 'Tenant enabled',
    disabled: 'Tenant disabled',
  },
  errors: {
    loadFailed: 'Failed to load tenants',
  },
};
