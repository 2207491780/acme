/** en-US depts resource pack (REQ-20261114-120104, org structure domain; keys aligned with zh-CN). */
export const depts = {
  title: 'Departments',
  reload: 'Refresh department tree',
  create: 'New department',
  columns: {
    deptName: 'Department name',
    status: 'Status',
    sortOrder: 'Order',
    actions: 'Actions',
  },
  actions: {
    edit: 'Edit',
    delete: 'Delete',
    deleteConfirm: 'Confirm deletion of department {{name}}?',
  },
  form: {
    editTitle: 'Edit department: {{name}}',
    createTitle: 'New department',
    parent: 'Parent department',
    parentPlaceholder: 'Leave empty for a root department',
    name: 'Department name',
    nameRequired: 'Please enter the department name',
    namePlaceholder: 'Department display name',
    sortOrder: 'Order',
    sortOrderRequired: 'Please enter the order number',
    status: 'Status',
    lockNote: 'Submitted with optimistic lock (version {{version}}); if conflicts occur, refresh and try again.',
    created: 'Department created',
    updated: 'Department updated',
  },
  errors: {
    loadFailed: 'Failed to load department tree',
    deleteFailed: 'Failed to delete the department, please try again later',
    deptHasChildren: 'The department has sub-departments and cannot be deleted',
    deptHasUsers: 'The department has users and cannot be deleted',
  },
  deleted: 'Department {{name}} deleted',
};
