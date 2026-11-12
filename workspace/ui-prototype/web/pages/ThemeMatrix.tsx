import { useState } from 'react';
import { Button, Card, ConfigProvider, Progress, Segmented, Space, Switch, Tag, Typography } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { ACCENT_ORDER, DENSITY, ON_ACCENT, TONES, type Accent } from '@acme/core';
import { buildTheme } from '../../theme';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Section } from '../components/blocks';

const { Text } = Typography;

/** F5 主题矩阵走查页：6 强调色 × 暗/亮 × 密度下的组件标本并排，用于新页面令牌覆盖度核对。 */
export default function ThemeMatrix() {
  const settings = useStore((s) => s.state.settings);
  const [previewDensity, setPreviewDensity] = useState<'dark' | 'light'>(settings.theme);

  return (
    <PageShell
      title="主题矩阵走查"
      domain="横向专项 F5"
      desc="新页面用的核心组件在 6 强调色 × 暗/亮 主题下的渲染矩阵；与 pnpm ui:guide 校验页互补。"
      extra={
        <Segmented
          value={previewDensity}
          onChange={(v) => setPreviewDensity(v as 'dark' | 'light')}
          options={[
            { value: 'dark', label: '深色标本' },
            { value: 'light', label: '浅色标本' },
          ]}
          aria-label="切换标本主题"
        />
      }
    >
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 14 }}>
        {ACCENT_ORDER.map((accent: Accent) => (
          <ConfigProvider
            key={accent}
            locale={zhCN}
            theme={buildTheme({ ...settings, theme: previewDensity, accent })}
          >
            <Card
              size="small"
              title={<Text strong>{accent} · #{ACCENT_ORDER.indexOf(accent) + 1}</Text>}
              style={{ marginBottom: 0 }}
            >
              <Space direction="vertical" size={10} style={{ width: '100%' }}>
                <Space wrap size={6}>
                  <Button type="primary">主按钮</Button>
                  <Button>次级</Button>
                  <Button danger>危险</Button>
                </Space>
                <Space wrap size={6}>
                  <Tag color="success">已发布</Tag>
                  <Tag color="warning">待发布</Tag>
                  <Tag color="error">失败</Tag>
                  <Tag color="processing">构建中</Tag>
                  <Tag color="purple">测试中</Tag>
                </Space>
                <Progress percent={62} size="small" strokeColor={TONES.info} />
                <Space size={8}>
                  <Switch defaultChecked aria-label={`${accent} 开关标本`} />
                  <Text style={{ fontSize: 12 }}>on-accent 前景应接近纯黑（#{ON_ACCENT[accent].slice(1)}）</Text>
                </Space>
                <div style={{ fontSize: 11, color: 'var(--muted)' }}>
                  圆角 {DENSITY[settings.density].radius} · 卡片内边距 {DENSITY[settings.density].cardPadding}
                </div>
              </Space>
            </Card>
          </ConfigProvider>
        ))}
      </div>

      <Section title="走查要点" style={{ marginTop: 16 }}>
        <HintText>
          核对：主按钮文字对比度（纯黑 on accent）、语义 Tag 在暗色算法下不回退出厂高饱和色、进度条取语义色而非强调色、
          开关选中态背景 = 强调色且前景 = ON_ACCENT。任何一格不合格 → 回 core/theme.ts 调令牌，跑 pnpm -r test + ui:guide。
        </HintText>
      </Section>
    </PageShell>
  );
}
