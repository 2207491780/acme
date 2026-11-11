import { useRef } from 'react';
import type { ReactNode } from 'react';
import { App, Button, Card, Col, Row, Select, Space, Switch, Typography } from 'antd';
import {
  DatabaseOutlined,
  DownloadOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import type { Accent } from '@acme/core';
import { useStore } from '../store/useStore';
import { PageHead } from '../components/PageHead';
import { ACCENTS, ACCENT_ORDER } from '../theme';
import { detectPlatform, download } from '../utils/dom';

const { Text } = Typography;

function SettingRow({ label, desc, control }: { label: string; desc: string; control: ReactNode }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, padding: '14px 0', borderBottom: '1px solid var(--border)' }}>
      <div>
        <Text strong>{label}</Text>
        <div><Text type="secondary" style={{ fontSize: 12 }}>{desc}</Text></div>
      </div>
      {control}
    </div>
  );
}

export function Settings() {
  const { message } = App.useApp();
  const settings = useStore((s) => s.state.settings);
  const state = useStore((s) => s.state);
  const dispatch = useStore((s) => s.dispatch);
  const resetData = useStore((s) => s.resetData);
  const fileRef = useRef<HTMLInputElement>(null);
  const platform = detectPlatform();

  const onImport = (file: File) => {
    const reader = new FileReader();
    reader.onload = () => {
      try {
        const data: unknown = JSON.parse(String(reader.result));
        const ok = dispatch({ type: 'IMPORT', data });
        message[ok ? 'success' : 'error'](ok ? '导入成功' : '未导入：文件格式无效或数据已被其他窗口更新');
      } catch {
        message.error('导入失败：无法解析 JSON');
      }
    };
    reader.readAsText(file);
  };

  const runtime =
    platform === 'desktop' && window.acmeDesktop
      ? { info: `Electron ${window.acmeDesktop.versions.electron} · ${window.acmeDesktop.platform}`, badge: 'Desktop' }
      : platform === 'mobile'
        ? { info: '响应式移动入口 · 共享 Web 业务层', badge: 'Mobile' }
        : { info: '浏览器 Web 端 · 本地持久化', badge: 'Web' };

  return (
    <>
      <PageHead eyebrow="Preferences" title="设置" sub="主题、密度、强调色与数据管理。所有偏好即时生效并持久化到本地。" />
      <Row gutter={[18, 18]}>
        <Col xs={24} lg={12}>
          <Card title={<Space><ThunderboltOutlined style={{ color: 'var(--accent)' }} /> 外观</Space>} style={{ height: '100%' }}>
            <SettingRow
              label="深色主题"
              desc="切换深色 / 浅色界面"
              control={
                <Switch
                  checked={settings.theme === 'dark'}
                  onChange={(checked) => dispatch({ type: 'UPDATE_SETTINGS', patch: { theme: checked ? 'dark' : 'light' } })}
                />
              }
            />
            <SettingRow
              label="界面密度"
              desc="舒适 / 紧凑布局"
              control={
                <Select
                  style={{ width: 120 }}
                  value={settings.density}
                  onChange={(v) => dispatch({ type: 'UPDATE_SETTINGS', patch: { density: v } })}
                  options={[
                    { value: 'comfortable', label: '舒适' },
                    { value: 'compact', label: '紧凑' },
                  ]}
                />
              }
            />
            <SettingRow
              label="强调色"
              desc="影响按钮、导航与高亮"
              control={
                <Space>
                  {ACCENT_ORDER.map((name: Accent) => (
                    <button
                      key={name}
                      aria-label={name}
                      title={name}
                      onClick={() => dispatch({ type: 'UPDATE_SETTINGS', patch: { accent: name } })}
                      style={{
                        width: 30,
                        height: 30,
                        borderRadius: '50%',
                        cursor: 'pointer',
                        background: ACCENTS[name],
                        border: settings.accent === name ? '2px solid var(--text)' : '2px solid transparent',
                        boxShadow: settings.accent === name ? `0 0 0 3px ${ACCENTS[name]}59` : 'none',
                      }}
                    />
                  ))}
                </Space>
              }
            />
            <SettingRow
              label="动效"
              desc="界面过渡与入场动画"
              control={
                <Switch
                  checked={settings.motion}
                  onChange={(checked) => dispatch({ type: 'UPDATE_SETTINGS', patch: { motion: checked } })}
                />
              }
            />
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card title={<Space><DatabaseOutlined style={{ color: 'var(--accent)' }} /> 数据管理</Space>} style={{ height: '100%' }}>
            <SettingRow
              label="导出数据"
              desc="下载当前发布单与应用 JSON"
              control={
                <Button
                  icon={<DownloadOutlined />}
                  onClick={() => {
                    download(`acme-frontend-ops-${new Date().toISOString().slice(0, 10)}.json`, JSON.stringify(state, null, 2));
                    message.success('数据已导出');
                  }}
                >
                  导出
                </Button>
              }
            />
            <SettingRow
              label="导入数据"
              desc="从 JSON 文件恢复"
              control={
                <>
                  <Button icon={<UploadOutlined />} onClick={() => fileRef.current?.click()}>导入</Button>
                  <input
                    ref={fileRef}
                    type="file"
                    accept="application/json,.json"
                    hidden
                    onChange={(e) => {
                      const file = e.target.files?.[0];
                      if (file) onImport(file);
                      e.target.value = '';
                    }}
                  />
                </>
              }
            />
            <SettingRow
              label="重置演示数据"
              desc="恢复初始种子数据"
              control={
                <Button
                  danger
                  icon={<ReloadOutlined />}
                  onClick={() => {
                    const ok = resetData();
                    message[ok ? 'success' : 'warning'](ok ? '已重置为演示数据' : '未重置：数据已变化，请检查后重试');
                  }}
                >
                  重置
                </Button>
              }
            />
            <SettingRow label="运行环境" desc={runtime.info} control={<Text style={{ color: 'var(--accent)' }}>{runtime.badge}</Text>} />
          </Card>
        </Col>
      </Row>
    </>
  );
}