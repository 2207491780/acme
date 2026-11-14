import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { HashRouter } from 'react-router-dom';
import { App as AntApp, ConfigProvider } from 'antd';
import enUS from 'antd/locale/en_US';
import zhCN from 'antd/locale/zh_CN';
import { useLocaleStore } from '@acme/core';
import { useStore } from './store/useStore';
import { buildTheme } from './theme';
import { App } from './App';
import { AppErrorBoundary } from './components/ErrorBoundary';
import './styles/global.css';

/**
 * MSW 接入（REQ-20261114-120103，AC-7）：本地 dev 默认开启（.env.development 的
 * VITE_ENABLE_MSW=false 可关闭，联调真实后端时使用）；生产构建零打包（动态导入不进产物）；
 * 未覆盖请求 bypass 透传（handler 只盖核心端点，其余请求照常代理到 WEB_API_TARGET）。
 */
async function enableMocking(): Promise<void> {
  if (!import.meta.env.DEV || import.meta.env.VITE_ENABLE_MSW === 'false') {
    return;
  }
  const { worker } = await import('./mocks/browser');
  await worker.start({ onUnhandledRequest: 'bypass' });
}

function Root() {
  const settings = useStore((s) => s.state.settings);
  // AntD 组件文案（分页/日期选择/确认按钮等）随展示 locale 联动（AC-1 全量文案）
  const locale = useLocaleStore((s) => s.locale);
  return (
    <ConfigProvider locale={locale === 'en-US' ? enUS : zhCN} theme={buildTheme(settings)}>
      <AntApp>
        <HashRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
          <AppErrorBoundary>
            <App />
          </AppErrorBoundary>
        </HashRouter>
      </AntApp>
    </ConfigProvider>
  );
}

void enableMocking().then(() => {
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <Root />
    </StrictMode>,
  );
});
