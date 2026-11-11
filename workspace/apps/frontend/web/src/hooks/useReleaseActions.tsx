import { App, Button } from 'antd';
import { useStore } from '../store/useStore';

export function useDeleteReleases() {
  const { notification, message } = App.useApp();
  const deleteReleases = useStore((s) => s.deleteReleases);
  const dispatch = useStore((s) => s.dispatch);

  return (ids: string[]) => {
    const items = deleteReleases(ids);
    if (!items.length) return;
    const key = `undo-${Date.now()}`;
    notification.warning({
      key,
      message: `已删除 ${items.length} 个发布单`,
      description: '删除后可在限定时间内撤销恢复。',
      duration: 5.2,
      actions: [
        <Button
          key="undo"
          type="link"
          onClick={() => {
            const ok = dispatch({ type: 'RESTORE_RELEASES', items });
            if (ok) {
              notification.destroy(key);
              message.success('已恢复删除的发布单');
            } else {
              message.warning('未恢复：数据可能已变化，请检查后重试');
            }
          }}
        >
          撤销
        </Button>,
      ],
    });
  };
}