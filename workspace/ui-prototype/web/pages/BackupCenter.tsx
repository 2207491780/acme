import { useMemo, useRef, useState } from 'react';
import { App, Button, Card, Popconfirm, Space, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { CloudUploadOutlined, DownloadOutlined, ImportOutlined } from '@ant-design/icons';
import { backupSnapshots, fmtFull, type BackupSnapshot } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Kpi, KpiRow, Section } from '../components/blocks';

const { Text } = Typography;

interface ImportPreview {
  ok: boolean;
  incomingReleases: number;
  incomingApps: number;
  diffNote: string;
  data: unknown;
}

/** W4 备份与迁移中心：快照清单、导出当前数据、导入预览 + 确认（导入走正式 IMPORT 口径，可真实写入）。 */
export default function BackupCenter() {
  const { message } = App.useApp();
  const state = useStore((s) => s.state);
  const dispatch = useStore((s) => s.dispatch);
  const snapshots = useMemo(() => backupSnapshots(state, Date.now()), [state]);
  const fileRef = useRef<HTMLInputElement>(null);
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [exporting, setExporting] = useState(false);

  const exportNow = () => {
    setExporting(true);
    const blob = new Blob([JSON.stringify(state, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `acme-backup-${new Date().toISOString().slice(0, 10)}.json`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 800);
    setTimeout(() => setExporting(false), 400);
    message.success('原型演示：备份已导出（真实数据）');
  };

  const onFile = (file: File) => {
    const reader = new FileReader();
    reader.onload = () => {
      try {
        const data: unknown = JSON.parse(String(reader.result));
        const incoming = data as { releases?: unknown[]; apps?: unknown[] } | null;
        if (!incoming || !Array.isArray(incoming.releases) || !Array.isArray(incoming.apps)) {
          message.error('导入预览失败：结构缺少 releases/apps');
          return;
        }
        const diffReleases = incoming.releases.length - state.releases.length;
        setPreview({
          ok: true,
          incomingReleases: incoming.releases.length,
          incomingApps: incoming.apps.length,
          diffNote:
            diffReleases === 0
              ? '发布单数量一致（可能为同一份快照）'
              : `导入后发布单将${diffReleases > 0 ? '增加' : '减少'} ${Math.abs(diffReleases)} 条`,
          data,
        });
      } catch {
        message.error('导入预览失败：无法解析 JSON');
      }
    };
    reader.readAsText(file);
  };

  const columns: ColumnsType<BackupSnapshot> = [
    { title: '快照', dataIndex: 'id', width: 100, render: (v: string, r) => (
      <Space size={4}>
        <Text strong style={{ fontFamily: 'var(--mono)', fontSize: 12 }}>{v}</Text>
        {r.tone === 'success' && <Tag color="success">最近</Tag>}
      </Space>
    ) },
    { title: '时间', dataIndex: 'createdAt', render: (v: string) => <Text type="secondary" style={{ fontSize: 12 }}>{fmtFull(v)}</Text> },
    { title: '发布单', dataIndex: 'releases', width: 90, render: (v: number) => <Text style={{ fontFamily: 'var(--mono)' }}>{v}</Text> },
    { title: '应用', dataIndex: 'apps', width: 70, render: (v: number) => <Text style={{ fontFamily: 'var(--mono)' }}>{v}</Text> },
    { title: '大小', dataIndex: 'sizeKb', width: 90, render: (v: number) => <Text style={{ fontFamily: 'var(--mono)' }}>{v} KB</Text> },
    { title: '', width: 110, render: (_, r) => (
      <Popconfirm title="恢复此快照会覆盖当前数据？" description="当前未导出的修改将丢失（W4 纪律：先导出再恢复）。" okText="恢复" cancelText="取消" okButtonProps={{ danger: true }}
        onConfirm={() => message.info(`原型演示：快照 ${r.id} 恢复仅本地生效`)}>
        <Button size="small" aria-label={`恢复快照 ${r.id}`}>恢复</Button>
      </Popconfirm>
    ) },
  ];

  return (
    <PageShell
      title="备份与迁移中心"
      domain="内容与治理域 W4"
      desc="快照清单 / 导出当前数据（真实下载）/ 导入预览 + 确认（导入走正式 IMPORT 口径，可真实写入 store）。"
      extra={
        <Button icon={<DownloadOutlined />} loading={exporting} onClick={exportNow}>
          导出当前数据
        </Button>
      }
    >
      <KpiRow>
        <Kpi label="快照数（演示）" value={snapshots.length} tone="info" />
        <Kpi label="当前发布单" value={state.releases.length} tone="violet" />
        <Kpi label="当前应用" value={state.apps.length} tone="success" />
        <Kpi label="导入预览" value={preview ? '已就绪' : '—'} tone={preview ? 'warning' : 'muted'} />
      </KpiRow>

      <Section
        title={
          <Space size={8} wrap>
            <span>备份快照（确定性演示）</span>
            <Button size="small" icon={<CloudUploadOutlined />} onClick={() => message.info('原型演示：创建快照仅本地生效')}>
              创建快照
            </Button>
          </Space>
        }
        style={{ marginBottom: 16 }}
      >
        <Table size="small" rowKey="id" columns={columns} dataSource={snapshots} pagination={false} aria-label="备份快照清单" />
      </Section>

      <Card size="small" title={<Space size={6}><ImportOutlined /> 导入数据（预览 → 确认）</Space>}>
        <Space direction="vertical" size={10} style={{ width: '100%' }}>
          <input
            ref={fileRef}
            type="file"
            accept="application/json,.json"
            hidden
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) onFile(file);
              e.target.value = '';
            }}
          />
          <Button icon={<ImportOutlined />} onClick={() => fileRef.current?.click()} aria-label="选择导入文件">
            选择 JSON 备份文件
          </Button>
          {preview && (
            <div style={{ padding: 12, borderRadius: 10, border: '1px solid var(--border)', background: 'var(--surface-2)', fontSize: 12.5 }}>
              <Space size={6} wrap style={{ marginBottom: 6 }}>
                <Tag color="success">结构合法</Tag>
                <Tag>发布单 {preview.incomingReleases}</Tag>
                <Tag>应用 {preview.incomingApps}</Tag>
              </Space>
              <div>{preview.diffNote}</div>
              <HintText style={{ marginTop: 6 }}>导入会整体替换当前数据（走正式 IMPORT：非法数据将被拒收且不覆盖）。</HintText>
              <Popconfirm
                title="确认导入？当前数据将被替换。"
                description="建议先「导出当前数据」留存（W4 纪律）。"
                okText="确认导入"
                cancelText="取消"
                okButtonProps={{ danger: true }}
                onConfirm={() => {
                  const ok = dispatch({ type: 'IMPORT', data: preview.data });
                  message[ok ? 'success' : 'error'](ok ? '导入成功（真实写入 store）' : '导入被拒收：数据非法或已被其他窗口更新');
                  setPreview(null);
                }}
              >
                <Button size="small" danger style={{ marginTop: 8 }}>确认导入</Button>
              </Popconfirm>
            </div>
          )}
        </Space>
      </Card>

      <HintText style={{ marginTop: 12 }}>
        本页是首个「写正式 store」的原型（IMPORT 为既有正式能力，Settings 页同款口径）；快照清单仍为确定性演示。
      </HintText>
    </PageShell>
  );
}
