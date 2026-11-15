/** zh-CN 部门管理资源包（REQ-20261114-120104，组织架构域）。 */
export const depts = {
  title: '部门管理',
  reload: '刷新部门树',
  create: '新建部门',
  columns: {
    deptName: '部门名称',
    status: '状态',
    sortOrder: '排序',
    actions: '操作',
  },
  actions: {
    edit: '编辑',
    delete: '删除',
    deleteConfirm: '确认删除部门 {{name}}？',
  },
  form: {
    editTitle: '编辑部门：{{name}}',
    createTitle: '新建部门',
    parent: '上级部门',
    parentPlaceholder: '不选则为根部门',
    name: '部门名称',
    nameRequired: '请输入部门名称',
    namePlaceholder: '部门显示名称',
    sortOrder: '排序',
    sortOrderRequired: '请输入排序号',
    status: '状态',
    lockNote: '按乐观锁提交（版本 {{version}}）；冲突时请刷新后重试。',
    created: '部门已创建',
    updated: '部门已更新',
  },
  errors: {
    loadFailed: '部门树加载失败',
    deleteFailed: '删除部门失败，请稍后重试',
    deptHasChildren: '存在子部门，无法删除',
    deptHasUsers: '部门下存在用户，无法删除',
  },
  deleted: '部门 {{name}} 已删除',
};
