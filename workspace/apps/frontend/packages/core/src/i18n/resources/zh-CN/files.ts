/** zh-CN 文件管理域资源包（文件列表 / 上传 / Excel 导入导出）。 */
export const files = {
  title: '文件管理',
  search: { placeholder: '搜索文件名' },
  reload: '刷新文件清单',
  total: '共 {{total}} 个文件',
  columns: {
    originalName: '文件名',
    size: '体积',
    contentType: 'Content-Type',
    biz: '业务',
    createdAt: '时间',
    actions: '操作',
  },
  actions: {
    download: '下载',
    delete: '删除',
    deleteConfirm: '确认删除文件 {{name}}？',
    upload: '上传文件',
    exportExcel: '导出 Excel 示例',
    importExcel: '导入 Excel 示例',
  },
  toast: {
    downloaded: 'Excel 示例已导出',
    fileDeleted: '文件 {{name}} 已删除',
    uploadSuccess: '{{name}} 上传成功',
    importDone: '导入完成：共 {{total}} 行，有效 {{valid}} 行，错误 {{errors}} 行',
    templateFailed: '模板下载失败，请稍后重试',
  },
  import: {
    label: '导入 Excel',
    template: '下载模板',
    reportTitle: '导入报告',
    ok: '知道了',
    statTotal: '总行数',
    statValid: '有效',
    statErrors: '错误',
    columns: {
      row: '行号',
      field: '字段',
      reason: '原因',
    },
    allValid: '全部行校验通过（validate-only 不落库）。',
  },
  errors: {
    loadFailed: '文件清单加载失败',
  },
};
