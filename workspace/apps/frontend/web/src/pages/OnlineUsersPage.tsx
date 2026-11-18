import { useState } from 'react';
import { App, Button, Input, Popconfirm, Space, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import {
  authApi,
  Auth,
  formatDate,
  systemErrorMessage,
  useCrud,
  useMeStore,
  type AdminOnlineUserView,
} from '@acme/core';
import { CrudTable } from '../components/CrudTable';

const { Text } = Typography;

/** 在线用户（REQ-20261114-120105 / REQ-20261117-061017，权限点 auth:online:list / auth:online:revoke）：
 *  活跃会话列表 + 单会话踢下线 + 按用户名批量下线（Popconfirm 确认，kicked/skipped 计数反馈；
 *  失败经 systemErrorMessage 统一转译 SESSION_NOT_FOUND / REVOKE_SUPERADMIN_FORBIDDEN，尾部附码）。
 *  CRUD 范本对齐 DeptsPage（非分页列表、行内动作按 crud.can 显隐）。 */
export function OnlineUsersPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const permissions = useMeStore((s) => s.me?.permissions) ?? [];

  const crud = useCrud<AdminOnlineUserView>({
    // 活跃会话全量列表（契约「列表」口径，非分页）：数组信封兼容运行时校验
    fetchPage: async ({ signal }) => {
      const rows = await authApi.onlineUsers(signal);
      return { records: Array.isArray(rows) ? rows : [], total: Array.isArray(rows) ? rows.length : 0 };
    },
    permissions,
    errorMessage: t('onlineUsers.errors.loadFailed'),
  });

  // 按用户名批量下线（REQ-20261117-061017）：DELETE /admin/v1/auth/online-users?username={username}
  const [kickUsername, setKickUsername] = useState('');

  const revoke = async (record: AdminOnlineUserView) => {
    try {
      await authApi.revokeOnlineUser(record.sessionId);
      message.success(t('onlineUsers.revoked', { name: record.username }));
      crud.reload();
    } catch (ex: unknown) {
      // 会话守卫（SESSION_NOT_FOUND / REVOKE_SUPERADMIN_FORBIDDEN）→ 统一转译反馈；失败不重取（行保留）
      message.error(systemErrorMessage(ex, t('onlineUsers.errors.revokeFailed')));
    }
  };

  const batchKick = async () => {
    const username = kickUsername.trim();
    if (!username) {
      message.warning(t('onlineUsers.batchKick.usernameRequired'));
      return;
    }
    try {
      const result = await authApi.onlineUsersBatchKick(username);
      const skipped = Number(result?.skippedCount ?? 0);
      const kicked = Number(result?.kickedCount ?? 0);
      message.success(
        skipped > 0
          ? t('onlineUsers.batchKick.result', { kicked, skipped })
          : t('onlineUsers.batchKick.resultNoSkip', { kicked }),
      );
      setKickUsername('');
      crud.reload();
    } catch (ex: unknown) {
      // 会话守卫（REVOKE_SUPERADMIN_FORBIDDEN 等）→ 统一转译反馈；失败不重取（行保留）
      message.error(systemErrorMessage(ex, t('onlineUsers.errors.kickFailed')));
    }
  };

  const columns: ColumnsType<AdminOnlineUserView> = [
    { title: t('onlineUsers.columns.username'), dataIndex: 'username', key: 'username', minWidth: 160, fixed: 'left', render: (v: string) => <Text code>{v}</Text> },
    { title: t('onlineUsers.columns.userType'), dataIndex: 'userType', key: 'userType', minWidth: 80, render: (v: string) => <Tag>{v}</Tag> },
    { title: t('onlineUsers.columns.ip'), dataIndex: 'ip', key: 'ip', minWidth: 140 },
    { title: t('onlineUsers.columns.loginAt'), dataIndex: 'loginAt', key: 'loginAt', minWidth: 170, render: (v: string) => formatDate(v) },
    { title: t('onlineUsers.columns.lastActiveAt'), dataIndex: 'lastActiveAt', key: 'lastActiveAt', minWidth: 170, render: (v: string) => formatDate(v) },
    {
      title: t('onlineUsers.columns.actions'), key: 'actions', minWidth: 120, fixed: 'right',
      render: (_, record) => (
        <Auth code="auth:online:revoke">
          <Popconfirm
            title={t('onlineUsers.revokeConfirm')}
            onConfirm={() => void revoke(record)}
          >
            <Button size="small" danger>{t('onlineUsers.revoke')}</Button>
          </Popconfirm>
        </Auth>
      ),
    },
  ];

  return (
    <CrudTable<AdminOnlineUserView>
      crud={crud}
      tableKey="onlineUsers"
      title={t('onlineUsers.title')}
      columns={columns}
      rowKey="sessionId"
      searchable={false}
      paged={false}
      reloadAriaLabel={t('onlineUsers.reload')}
      totalText={(total) => t('onlineUsers.total', { total })}
      actions={(
        <Auth code="auth:online:revoke">
          <Space size={4}>
            <Input
              allowClear
              placeholder={t('onlineUsers.batchKick.placeholder')}
              value={kickUsername}
              onChange={(e) => setKickUsername(e.target.value)}
              style={{ width: 180 }}
            />
            <Popconfirm
              title={t('onlineUsers.batchKick.confirm', { username: kickUsername.trim() || '—' })}
              onConfirm={() => void batchKick()}
            >
              <Button danger>{t('onlineUsers.batchKick.submit')}</Button>
            </Popconfirm>
          </Space>
        </Auth>
      )}
    />
  );
}
