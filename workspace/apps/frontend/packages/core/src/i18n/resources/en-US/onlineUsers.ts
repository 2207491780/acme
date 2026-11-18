/** en-US online users resource pack (REQ-20261114-120105). */
export const onlineUsers = {
  title: 'Online users',
  reload: 'Refresh online users',
  total: '{{total}} users in total',
  columns: {
    username: 'Username',
    userType: 'Channel',
    ip: 'IP',
    loginAt: 'Login time',
    lastActiveAt: 'Last active',
    actions: 'Actions',
  },
  revoke: 'Revoke session',
  revokeConfirm: 'Revoke this user\'s session? It takes effect immediately.',
  revoked: 'Session revoked: {{name}}',
  batchKick: {
    placeholder: 'Kick all sessions by username',
    submit: 'Kick off',
    confirm: 'Revoke all sessions of {{username}}? Super-admin sessions are skipped.',
    result: 'Revoked {{kicked}} session(s), skipped {{skipped}} (super-admin protected)',
    resultNoSkip: 'Revoked {{kicked}} session(s)',
    usernameRequired: 'Enter a username to kick off',
  },
  errors: {
    loadFailed: 'Failed to load online users',
    revokeFailed: 'Failed to revoke the session',
    kickFailed: 'Failed to kick the user off',
    sessionNotFound: 'Target session not found (may have been signed out or expired)',
    revokeSuperadminForbidden: 'Super-admin sessions cannot be revoked by non super-admins',
  },
};
