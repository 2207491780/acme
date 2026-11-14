import { App, Button } from 'antd';
import { useTranslation } from 'react-i18next';
import { useStore } from '../store/useStore';

export function useDeleteReleases() {
  const { t } = useTranslation();
  const { notification, message } = App.useApp();
  const deleteReleases = useStore((s) => s.deleteReleases);
  const dispatch = useStore((s) => s.dispatch);

  return (ids: string[]) => {
    const items = deleteReleases(ids);
    if (!items.length) return;
    const key = `undo-${Date.now()}`;
    notification.warning({
      key,
      message: t('releases.deleteActions.deleted', { n: items.length }),
      description: t('releases.deleteActions.desc'),
      duration: 5.2,
      actions: [
        <Button
          key="undo"
          type="link"
          onClick={() => {
            const ok = dispatch({ type: 'RESTORE_RELEASES', items });
            if (ok) {
              notification.destroy(key);
              message.success(t('releases.deleteActions.restored'));
            } else {
              message.warning(t('releases.deleteActions.restoreConflict'));
            }
          }}
        >
          {t('common.actions.undo')}
        </Button>,
      ],
    });
  };
}
