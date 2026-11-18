/** zh-CN 通用组件资源包：错误边界 / 字典选择器 / 通知中心。 */
export const components = {
  errorBoundary: {
    title: '页面出错了',
    desc: '页面渲染发生异常，可尝试重试；问题持续请刷新页面。',
    appTitle: '应用出现异常',
    appDesc: '发生了未捕获的错误。可刷新页面恢复；问题持续请联系管理员。',
    detail: '错误信息',
    retry: '重试',
    reload: '刷新页面',
  },
  dictSelect: {
    retry: '重试',
  },
  lockScreen: {
    title: '屏幕已锁定',
    desc: '输入锁屏口令继续操作',
    placeholder: '请输入锁屏口令',
    unlock: '解锁',
    unlockFailed: '解锁失败，请重试',
    forgot: '忘记口令？重新登录',
    setDesc: '首次锁屏：设置锁屏口令后解锁',
    setPlaceholder: '请输入新锁屏口令',
    confirmPlaceholder: '请再次输入锁屏口令',
    set: '设置口令',
    setEmpty: '口令不能为空',
    setMismatch: '两次输入的口令不一致',
  },
  notification: {
    center: '通知中心',
    markAllRead: '全部已读',
    read: '已读',
    unread: '未读',
    inboxLoadFailed: '收件箱加载失败',
    markReadFailed: '标记已读失败，请稍后重试',
    markAllReadFailed: '全部已读失败，请稍后重试',
  },
};
