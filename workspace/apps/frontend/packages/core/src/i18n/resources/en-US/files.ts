/** en-US files domain resource pack (file list / upload / Excel import & export). */
export const files = {
  title: 'Files',
  search: { placeholder: 'Search file names' },
  reload: 'Refresh files',
  total: '{{total}} files in total',
  columns: {
    originalName: 'File name',
    size: 'Size',
    contentType: 'Content-Type',
    biz: 'Business',
    createdAt: 'Time',
    actions: 'Actions',
  },
  actions: {
    download: 'Download',
    delete: 'Delete',
    deleteConfirm: 'Confirm deletion of file {{name}}?',
    upload: 'Upload file',
    exportExcel: 'Export Excel sample',
    importExcel: 'Import Excel sample',
  },
  toast: {
    downloaded: 'Excel sample exported',
    fileDeleted: 'File {{name}} deleted',
    uploadSuccess: '{{name}} uploaded',
    importDone: 'Import finished: {{total}} rows total, {{valid}} valid, {{errors}} with errors',
    templateFailed: 'Template download failed, please try again later',
  },
  import: {
    label: 'Import Excel',
    template: 'Download template',
    reportTitle: 'Import report',
    ok: 'Got it',
    statTotal: 'Total rows',
    statValid: 'Valid',
    statErrors: 'Errors',
    columns: {
      row: 'Row',
      field: 'Field',
      reason: 'Reason',
    },
    allValid: 'All rows passed validation (validate-only, nothing persisted).',
  },
  errors: {
    loadFailed: 'Failed to load files',
  },
};
