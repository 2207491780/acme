import { useCallback, useEffect, useState } from 'react';
import { App, Badge, Button, Drawer, List, Tag, Typography } from 'antd';
import { BellOutlined } from '@ant-design/icons';
import { formatDate, notifyApi, notifyErrorMessage, useMeStore, useNotifyStore } from '@acme/core';
import type { AdminMessageView } from '@acme/core';

/**
 * Header 通知铃铛 + 通知中心抽屉（REQ-20261114-120102，AC-10/AC-11；契约裁决⑪表面钉死）：
 * - 命名导出、零 props；挂载即拉取 unread-count 兜底（WS 不可用时的降级来源）；
 * - 未读徽标 = antd Badge count 模式（红点语义由徽标红色承载），数据源 useNotifyStore.unreadCount
 *   （WS message:new 经 App 装配实时 +1）；
 * - 点击开「通知中心」抽屉：收件箱 {records,total} 分页、点击条目标题=单条已读、「全部已读」按钮；
 * - 门禁（三段式 fail-closed）：铃铛/收件箱可见 = system:message:list，已读动作 = system:message:read。
 */

const INBOX_PAGE_SIZE = 10;

export function NotificationBell() {
  const { message: antdMessage } = App.useApp();
  const permissions = useMeStore((s) => s.me?.permissions ?? []);
  const canList = permissions.includes('system:message:list');
  const canRead = permissions.includes('system:message:read');
  const unreadCount = useNotifyStore((s) => s.unreadCount);

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [page, setPage] = useState(1);
  const [records, setRecords] = useState<AdminMessageView[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);

  // 页面加载兜底：挂载即拉取未读数（AC-10；WS 链路不可用时的降级来源）
  useEffect(() => {
    if (!canList) {
      return;
    }
    void useNotifyStore.getState().refreshUnread();
  }, [canList]);

  const loadInbox = useCallback(
    async (targetPage: number) => {
      setLoading(true);
      try {
        const result = await notifyApi.messageList(targetPage, INBOX_PAGE_SIZE);
        setRecords(result.records ?? []);
        setTotal(Number(result.total));
      } catch (ex: unknown) {
        antdMessage.error(notifyErrorMessage(ex, '收件箱加载失败'));
      } finally {
        setLoading(false);
      }
    },
    [antdMessage],
  );

  // 抽屉打开/翻页即拉取收件箱（CrudTable 分页口径 page 从 1 起）
  useEffect(() => {
    if (drawerOpen) {
      void loadInbox(page);
    }
  }, [drawerOpen, page, loadInbox]);

  const markSingleRead = async (record: AdminMessageView) => {
    if (!canRead || record.isRead) {
      return; // fail-closed：无 system:message:read 不发已读请求
    }
    try {
      await useNotifyStore.getState().markRead(record.id);
      await loadInbox(page);
    } catch (ex: unknown) {
      antdMessage.error(notifyErrorMessage(ex, '标记已读失败，请稍后重试'));
    }
  };

  const markAllRead = async () => {
    try {
      await useNotifyStore.getState().markAllRead();
      await loadInbox(page);
    } catch (ex: unknown) {
      antdMessage.error(notifyErrorMessage(ex, '全部已读失败，请稍后重试'));
    }
  };

  if (!canList) {
    return null;
  }

  return (
    <>
      <Button
        type="text"
        aria-label="通知中心"
        onClick={() => setDrawerOpen(true)}
        icon={(
          <Badge count={unreadCount} size="small">
            <BellOutlined style={{ fontSize: 17 }} />
          </Badge>
        )}
      />
      <Drawer
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        title="通知中心"
        width={420}
        destroyOnClose
        styles={{ body: { paddingTop: 0 } }}
        extra={canRead ? <Button size="small" onClick={() => void markAllRead()}>全部已读</Button> : undefined}
      >
        <List<AdminMessageView>
          size="small"
          loading={loading}
          dataSource={records}
          renderItem={(item) => (
            <List.Item
              className="notify-inbox-item"
              onClick={() => void markSingleRead(item)}
            >
              <div className="notify-inbox-body">
                <div className="notify-inbox-row">
                  <Typography.Text strong={!item.isRead} className="notify-inbox-title">
                    {item.title}
                  </Typography.Text>
                  {item.isRead ? <Tag color="default">已读</Tag> : <Tag color="processing">未读</Tag>}
                </div>
                <Typography.Text type="secondary" className="notify-inbox-time">
                  {formatDate(item.createdAt)}
                </Typography.Text>
              </div>
            </List.Item>
          )}
          pagination={{
            current: page,
            pageSize: INBOX_PAGE_SIZE,
            total,
            size: 'small',
            showSizeChanger: false,
            onChange: (next) => setPage(next),
          }}
        />
      </Drawer>
    </>
  );
}
