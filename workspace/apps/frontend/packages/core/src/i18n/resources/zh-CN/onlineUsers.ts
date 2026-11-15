/** zh-CN 在线用户资源包（REQ-20261114-120105）。 */
export const onlineUsers = {
  title: '在线用户',
  reload: '刷新在线用户',
  total: '共 {{total}} 人',
  columns: {
    username: '用户名',
    userType: '端',
    ip: 'IP',
    loginAt: '登录时间',
    lastActiveAt: '最近活跃',
    actions: '操作',
  },
  revoke: '踢下线',
  revokeConfirm: '确定将该用户踢下线？其当前会话将立即失效。',
  revoked: '已踢下线：{{name}}',
  errors: {
    loadFailed: '在线用户加载失败',
    revokeFailed: '踢下线操作失败',
    sessionNotFound: '目标会话不存在（可能已注销或过期）',
    revokeSuperadminForbidden: '超级管理员会话不可被非超管踢下线',
  },
};
