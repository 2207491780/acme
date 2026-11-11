import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { HashRouter } from 'react-router-dom';
import { App as AntApp, ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { useStore } from './store/useStore';
import { buildTheme } from './theme';
import { App } from './App';
import './styles/global.css';

function Root() {
  const settings = useStore((s) => s.state.settings);
  return (
    <ConfigProvider locale={zhCN} theme={buildTheme(settings)}>
      <AntApp>
        <HashRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
          <App />
        </HashRouter>
      </AntApp>
    </ConfigProvider>
  );
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Root />
  </StrictMode>,
);