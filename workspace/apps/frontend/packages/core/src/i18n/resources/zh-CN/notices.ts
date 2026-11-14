/** zh-CN 通知公告域资源包（REQ-20261114-120102：公告管理 + 富文本编辑器）。 */
export const notices = {
  title: '通知公告',
  search: { placeholder: '搜索公告标题' },
  reload: '刷新公告',
  create: '新建',
  status: {
    DRAFT: '草稿',
    PUBLISHED: '已发布',
    OFFLINE: '已下线',
  },
  columns: {
    title: '标题',
    status: '状态',
    publishAt: '发布时间',
    actions: '操作',
  },
  actions: {
    edit: '编辑',
    publish: '发布',
    offline: '下线',
    delete: '删除',
    deleteConfirm: '确认删除公告「{{title}}」？',
  },
  toast: {
    publishSuccess: '公告已发布，全员站内信已生成',
    offlineSuccess: '公告已下线',
    deleteSuccess: '公告已删除',
    publishFailed: '发布失败，请稍后重试',
    offlineFailed: '下线失败，请稍后重试',
    deleteFailed: '删除失败，请稍后重试',
    saveFailed: '保存失败，请稍后重试',
  },
  form: {
    editTitle: '编辑公告',
    createTitle: '新建公告',
    title: '标题',
    titleRequired: '请输入公告标题',
    titlePlaceholder: '公告标题',
    content: '正文',
    contentRequired: '请输入公告正文',
    lockNote: '按乐观锁提交（版本 {{version}}）。',
    created: '公告已创建',
    updated: '公告已更新',
  },
  editor: {
    ariaLabel: '公告正文',
  },
  errors: {
    loadFailed: '公告列表加载失败',
  },
};
