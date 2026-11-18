import type { ReactNode } from 'react';
import { Alert, Button, Card } from 'antd';
import { useTranslation } from 'react-i18next';
import { useMeStore } from '@acme/core';
import { ErrorBoundary } from './ErrorBoundary';

/**
 * 页面级权限门禁（p2-admin，REQ-20261119-063129 契约①自 App.tsx 提取）：
 * 画像未加载放行占位（避免闪烁），已加载缺权限码 → 403 提示。
 * 加载失败进入错误态并给出重试入口（review 修复：此前失败后所有权限页永远停留在“加载中”）。
 * 逐 tab 包裹（TabKeepAlive）与逃生口 Routes（App.tsx）共用同一实现。
 */
export function PermissionGate({ permission, children }: { permission: string; children: ReactNode }) {
  const { t } = useTranslation();
  const me = useMeStore((s) => s.me);
  const loading = useMeStore((s) => s.loading);
  const error = useMeStore((s) => s.error);
  if (!me) {
    if (error && !loading) {
      return (
        <Card>
          <Alert
            type="error"
            showIcon
            message={t('nav.permissionGate.loadFailed')}
            description={t('nav.permissionGate.loadFailedDesc', { error })}
            action={
              <Button size="small" onClick={() => void useMeStore.getState().load()}>
                {t('common.actions.retry')}
              </Button>
            }
          />
        </Card>
      );
    }
    return <div style={{ padding: 32, textAlign: 'center', color: 'var(--muted)' }}>{t('nav.permissionGate.loading')}</div>;
  }
  if (!me.permissions.includes(permission)) {
    return (
      <Card>
        <Alert
          type="warning"
          showIcon
          message={t('nav.permissionGate.denied')}
          description={t('nav.permissionGate.deniedDesc', { permission })}
        />
      </Card>
    );
  }
  return <>{children}</>;
}

/** 页面级局部错误边界：页面渲染抛错 → 局部兜底 UI + 重试（REQ-20261114-120103，AC-3）。 */
export function PageBoundary({ children }: { children: ReactNode }) {
  return <ErrorBoundary variant="page">{children}</ErrorBoundary>;
}
