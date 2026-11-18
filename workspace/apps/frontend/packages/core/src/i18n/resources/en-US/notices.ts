/** en-US notices domain resource pack (REQ-20261114-120102: notice admin + rich text editor). */
export const notices = {
  title: 'Notices',
  search: { placeholder: 'Search notice titles' },
  reload: 'Refresh notices',
  create: 'New',
  status: {
    DRAFT: 'Draft',
    PUBLISHED: 'Published',
    OFFLINE: 'Offline',
  },
  columns: {
    title: 'Title',
    status: 'Status',
    publishAt: 'Published at',
    actions: 'Actions',
  },
  actions: {
    edit: 'Edit',
    publish: 'Publish',
    offline: 'Take offline',
    delete: 'Delete',
    deleteConfirm: 'Confirm deletion of notice "{{title}}"?',
  },
  toast: {
    publishSuccess: 'Notice published; in-app messages generated for all users',
    offlineSuccess: 'Notice taken offline',
    deleteSuccess: 'Notice deleted',
    publishFailed: 'Publish failed, please try again later',
    offlineFailed: 'Offline failed, please try again later',
    deleteFailed: 'Deletion failed, please try again later',
    saveFailed: 'Save failed, please try again later',
  },
  form: {
    editTitle: 'Edit notice',
    createTitle: 'New notice',
    title: 'Title',
    titleRequired: 'Please enter the notice title',
    titlePlaceholder: 'Notice title',
    content: 'Content',
    contentRequired: 'Please enter the notice content',
    lockNote: 'Submitted with optimistic lock (version {{version}}).',
    created: 'Notice created',
    updated: 'Notice updated',
  },
  editor: {
    ariaLabel: 'Notice content',
  },
  errors: {
    loadFailed: 'Failed to load notices',
  },
};
