import { useState } from 'react';
import { App, Button, Input, Space, Switch, Tag, Typography } from 'antd';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Row, Section } from '../components/blocks';

const { Text } = Typography;

const SHORTCUTS = [
  { keys: 'Ctrl / ⌘ + K', action: '全局命令面板（F2）' },
  { keys: 'Ctrl / ⌘ + F', action: '聚焦搜索' },
  { keys: 'G 然后 D', action: '跳转概览' },
  { keys: 'G 然后 R', action: '跳转发布单' },
  { keys: '?', action: '显示快捷键速查' },
];

const I18N_SAMPLE = [
  { key: 'release.status.published', zh: '已发布', en: 'Published' },
  { key: 'release.status.approved', zh: '待发布', en: 'Ready to publish' },
  { key: 'release.action.retry', zh: '重试构建', en: 'Retry build' },
  { key: 'settings.density.compact', zh: '紧凑', en: 'Compact' },
];

/** E3 设置扩展：主题自定义预览、快捷键管理、i18n 预览（原型仅本地生效）。 */
export default function SettingsExt() {
  const { message } = App.useApp();
  const settings = useStore((s) => s.state.settings);
  const dispatch = useStore((s) => s.dispatch);
  const [capture, setCapture] = useState(false);

  return (
    <PageShell
      title="设置扩展"
      domain="应用与配置域 E3"
      desc="设置页的演进方向原型：主题预览面板、快捷键管理与多语言预览；当前正式设置页保持不变。"
    >
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
        <Section title="主题预览面板">
          <Row label="深色 / 浅色">
            <Switch
              checked={settings.theme === 'dark'}
              onChange={(v) => dispatch({ type: 'UPDATE_SETTINGS', patch: { theme: v ? 'dark' : 'light' } })}
              aria-label="切换主题"
            />
            <Text type="secondary" style={{ fontSize: 12 }}>实时写入正式设置（可逆）</Text>
          </Row>
          <div style={{ display: 'flex', gap: 10, marginTop: 10 }}>
            {['compact', 'comfortable'].map((d) => (
              <div
                key={d}
                style={{
                  flex: 1,
                  padding: 12,
                  borderRadius: 12,
                  border: settings.density === d ? '2px solid var(--accent)' : '1px solid var(--border)',
                  background: 'var(--surface-2)',
                }}
              >
                <div style={{ fontSize: 11, color: 'var(--muted)', marginBottom: 6 }}>{d === 'compact' ? '紧凑' : '舒适'}</div>
                <div style={{ height: settings.density === d ? 10 : 14, borderRadius: 6, background: 'var(--accent)', opacity: 0.35, marginBottom: 6 }} />
                <div style={{ height: settings.density === d ? 8 : 11, borderRadius: 6, background: 'var(--surface-3)', width: '70%' }} />
              </div>
            ))}
          </div>
          <HintText style={{ marginTop: 10 }}>成熟化时增加「跟随系统」与对比度实时校验。</HintText>
        </Section>

        <Section title="快捷键管理">
          <Space direction="vertical" size={0} style={{ width: '100%' }}>
            {SHORTCUTS.map((s) => (
              <Row key={s.keys} label={s.keys}>
                <Text style={{ fontSize: 13 }}>{s.action}</Text>
              </Row>
            ))}
          </Space>
          <Row label="自定义捕获">
            <Switch checked={capture} onChange={setCapture} aria-label="快捷键自定义捕获" />
            <Button size="small" disabled={!capture} onClick={() => message.info('原型演示：请按下组合键（未实现监听）')}>
              录制按键
            </Button>
          </Row>
          <HintText style={{ marginTop: 6 }}>成熟化时监听 keydown 序列并写入本地偏好。</HintText>
        </Section>
      </div>

      <Section title="多语言预览（zh-CN / en-US）" style={{ marginTop: 16 }}>
        <div style={{ display: 'flex', flexDirection: 'column' }}>
          {I18N_SAMPLE.map((row) => (
            <div key={row.key} style={{ display: 'flex', gap: 12, padding: '8px 0', borderBottom: '1px solid var(--border)', alignItems: 'center' }}>
              <Text style={{ fontFamily: 'var(--mono)', fontSize: 11, width: 210, flex: 'none' }}>{row.key}</Text>
              <Tag style={{ flex: 'none' }}>中文：{row.zh}</Tag>
              <Tag style={{ flex: 'none' }}>EN: {row.en}</Tag>
            </div>
          ))}
        </div>
        <Row label="快速切换试试" >
          <Input
            style={{ width: 220 }}
            placeholder="输入 key 查看翻译（本地演示）"
            aria-label="翻译查询"
            onPressEnter={(e) => message.info(`原型演示：查表返回（${(e.target as HTMLInputElement).value || '空'}）`)}
          />
        </Row>
        <HintText style={{ marginTop: 8 }}>成熟化时引入 i18n 资源包并接入 AntD locale（当前已用 zh_CN）。</HintText>
      </Section>
    </PageShell>
  );
}
