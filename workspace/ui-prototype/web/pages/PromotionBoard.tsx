import { useState } from 'react';
import { App, Button, Card, Space, Tag } from 'antd';
import { ArrowLeftOutlined, ArrowRightOutlined } from '@ant-design/icons';
import { ENVS, ENV_ANTD, envLabel, PROMOTION_COLUMNS, platformShort, statusLabel, statusTone, TONE_ANTD } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Section } from '../components/blocks';
import { EmptyState } from '../components/states';

/** B3 环境晋升看板：dev → test → prod 看板列 + 晋升/退回操作（原型仅本地生效）。 */
export default function PromotionBoard() {
  const { message } = App.useApp();
  const releases = useStore((s) => s.state.releases);
  // 本地副本：env 由晋升/退回操作改写，模拟跨环境流转
  const [localEnv, setLocalEnv] = useState<Record<string, (typeof ENVS)[number]>>({});
  const envOf = (id: string, fallback: (typeof ENVS)[number]) => localEnv[id] ?? fallback;

  const move = (releaseId: string, from: (typeof ENVS)[number], dir: 1 | -1) => {
    const to = ENVS[ENVS.indexOf(from) + dir];
    if (!to) return;
    setLocalEnv((cur) => ({ ...cur, [releaseId]: to }));
    message.info(`原型演示：${releaseId} ${envLabel(from)} → ${envLabel(to)}（仅本地生效）`);
  };

  return (
    <PageShell
      title="环境晋升看板"
      domain="流程配置域 B3"
      desc="dev → test → prod 三列看板：发布单随环境晋升/退回流转，与 A4 日历、A5 回滚共享环境口径。"
    >
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 14 }}>
        {PROMOTION_COLUMNS.map(({ env, label }, colIdx) => {
          const cards = releases.filter((r) => envOf(r.id, r.env) === env && !['cancelled'].includes(r.status));
          return (
            <Section key={env} title={<Space size={6}><Tag color={ENV_ANTD[env]}>{label}</Tag><span>{cards.length} 个</span></Space>}>
              {cards.length === 0 ? (
                <EmptyState title="空列" desc="暂无该环境的发布单。" />
              ) : (
                <Space direction="vertical" size={8} style={{ width: '100%' }}>
                  {cards.map((r) => (
                    <Card key={r.id} size="small" style={{ borderRadius: 12 }}>
                      <Space wrap size={6} style={{ marginBottom: 4 }}>
                        <Tag color="default">{r.id}</Tag>
                        <Tag color="processing">{platformShort(r.platform)}</Tag>
                        <Tag color={TONE_ANTD[statusTone(r.status)]}>{statusLabel(r.status)}</Tag>
                      </Space>
                      <div style={{ fontSize: 13, marginBottom: 8 }}>{r.title}</div>
                      <div style={{ display: 'flex', gap: 6 }}>
                        <Button
                          size="small"
                          icon={<ArrowLeftOutlined />}
                          disabled={colIdx === 0}
                          onClick={() => move(r.id, envOf(r.id, r.env), -1)}
                          aria-label={`${r.id} 退回上一环境`}
                        >
                          退回
                        </Button>
                        <Button
                          size="small"
                          type="primary"
                          ghost
                          disabled={colIdx === PROMOTION_COLUMNS.length - 1 || r.status === 'failed'}
                          onClick={() => move(r.id, envOf(r.id, r.env), 1)}
                          aria-label={`${r.id} 晋升下一环境`}
                        >
                          晋升 <ArrowRightOutlined />
                        </Button>
                      </div>
                    </Card>
                  ))}
                </Space>
              )}
            </Section>
          );
        })}
      </div>
      <HintText style={{ marginTop: 12 }}>
        成熟化规则：prod 晋升强制走 B1 的双人审批；failed 单禁止晋升（本原型已禁用按钮）。
      </HintText>
    </PageShell>
  );
}
