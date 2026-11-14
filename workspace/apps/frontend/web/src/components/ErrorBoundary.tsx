import { Component, type ReactNode } from 'react';
import { Alert, Button, Space, Typography } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@acme/core';

/**
 * React 错误边界（REQ-20261114-120103，裁决③）：
 * - class 组件 ErrorBoundary：页面级局部边界（default，兜底 UI + 重试）与 App 级最终防线
 *   （variant='app'，全局兜底 + 刷新入口）共用同一实现，经 variant 区分兜底文案与动作；
 * - 兜底文案接入既有错误码转译风格（ApiError 展示 `message（code）`，尾部附码便于定位）；
 * - 重试 = 复位边界状态（错误子树已卸载，复位后整树重挂载）；App 级另提供刷新入口。
 */

export type ErrorBoundaryVariant = 'page' | 'app';

interface ErrorBoundaryProps {
  children: ReactNode;
  /** page=页面级局部边界（重试恢复）；app=App 级最终防线（刷新恢复）。 */
  variant?: ErrorBoundaryVariant;
}

interface ErrorBoundaryState {
  error: Error | null;
}

/** 兜底 UI（函数组件承载 i18n 文案；错误码转译风格与页面反馈一致）。 */
function BoundaryFallback({ error, variant, onRetry }: { error: Error; variant: ErrorBoundaryVariant; onRetry: () => void }) {
  const { t } = useTranslation();
  const isApp = variant === 'app';
  const detail = error instanceof ApiError && error.code
    ? `${error.message}（${error.code}）`
    : error.message;
  return (
    <div style={{ maxWidth: 560, margin: '48px auto' }} role="alert">
      <Alert
        type="error"
        showIcon
        message={isApp ? t('components.errorBoundary.appTitle') : t('components.errorBoundary.title')}
        description={(
          <Space direction="vertical" size={8}>
            <span>{isApp ? t('components.errorBoundary.appDesc') : t('components.errorBoundary.desc')}</span>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('components.errorBoundary.detail')}：{detail || t('components.errorBoundary.appTitle')}
            </Typography.Text>
          </Space>
        )}
        action={(
          <Space size={4}>
            <Button size="small" onClick={onRetry}>{t('components.errorBoundary.retry')}</Button>
            {isApp ? (
              <Button size="small" icon={<ReloadOutlined />} onClick={() => window.location.reload()}>
                {t('components.errorBoundary.reload')}
              </Button>
            ) : null}
          </Space>
        )}
      />
    </div>
  );
}

/** 通用错误边界（页面级：包住路由页面/局部区块；渲染抛错 → 兜底 UI + 重试，AC-3）。 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  override state: ErrorBoundaryState = { error: null };

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error };
  }

  override componentDidCatch(error: Error): void {
    // 边界兜底即上报口径：控制台留档（含堆栈），后续接入上报通道时在此扩展
    console.error('[ErrorBoundary]', error);
  }

  private readonly reset = () => {
    this.setState({ error: null });
  };

  override render(): ReactNode {
    if (this.state.error) {
      return <BoundaryFallback error={this.state.error} variant={this.props.variant ?? 'page'} onRetry={this.reset} />;
    }
    return this.props.children;
  }
}

/** App 级最终防线（main.tsx 挂载点外层包一层：未捕获错误不白屏，兜底含刷新入口，AC-3）。 */
export function AppErrorBoundary({ children }: { children: ReactNode }) {
  return <ErrorBoundary variant="app">{children}</ErrorBoundary>;
}
