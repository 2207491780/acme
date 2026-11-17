import { App, Button, Popconfirm, Space, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import {
  authApi,
  formatDate,
  systemErrorMessage,
  useCrud,
  useMeStore,
  type AdminOnlineUserView,
} from '@acme/core';
import { CrudTable } from '../components/CrudTable';

const { Text } = Typography;

/** 在线用户（REQ-20261114-120105，权限点 auth:online:list / auth:online:revoke）：
 *  活跃会话列表 + 踢下线操作（Popconfirm 确认 + 成功/失败 message 反馈；失败经
 *  systemErrorMessage 统一转译 SESSION_NOT_FOUND / REVOKE_SUPERADMIN_FORBIDDEN，尾部附码）。
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

  const columns: ColumnsType<AdminOnlineUserView> = [
    { title: t('onlineUsers.columns.username'), dataIndex: 'username', key: 'username', minWidth: 160, fixed: 'left', render: (v: string) => <Text code>{v}</Text> },
    { title: t('onlineUsers.columns.userType'), dataIndex: 'userType', key: 'userType', minWidth: 80, render: (v: string) => <Tag>{v}</Tag> },
    { title: t('onlineUsers.columns.ip'), dataIndex: 'ip', key: 'ip', minWidth: 140 },
    { title: t('onlineUsers.columns.loginAt'), dataIndex: 'loginAt', key: 'loginAt', minWidth: 170, render: (v: string) => formatDate(v) },
    { title: t('onlineUsers.columns.lastActiveAt'), dataIndex: 'lastActiveAt', key: 'lastActiveAt', minWidth: 170, render: (v: string) => formatDate(v) },
    {
      title: t('onlineUsers.columns.actions'), key: 'actions', minWidth: 120, fixed: 'right',
      render: (_, record) => (
        crud.can('auth:online:revoke') ? (
          <Popconfirm
            title={t('onlineUsers.revokeConfirm')}
            onConfirm={() => void revoke(record)}
          >
            <Button size="small" danger>{t('onlineUsers.revoke')}</Button>
          </Popconfirm>
        ) : (
          <Space size={4} />
        )
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
    />
  );
}
