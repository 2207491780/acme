import { useMemo, useState } from 'react';
import { Button, Dropdown, Segmented, Space, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { SettingOutlined } from '@ant-design/icons';
import { fmtFull, statusLabel, statusTone, TONE_ANTD } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Section } from '../components/blocks';

const { Text } = Typography;

const ALL_COLUMNS = [
  { key: 'id', label: '发布单 ID' },
  { key: 'title', label: '标题' },
  { key: 'env', label: '环境' },
  { key: 'owner', label: '负责人' },
  { key: 'version', label: '版本' },
  { key: 'updated', label: '最近更新' },
] as const;

/** X2 表格进阶模式：展开行、列显示自定义、密度切换、固定列（大宽表演示）。 */
export default function AdvancedTable() {
  const state = useStore((s) => s.state);
  const [visible, setVisible] = useState<string[]>(ALL_COLUMNS.map((c) => c.key));
  const [density, setDensity] = useState<'small' | 'middle' | 'large'>('middle');

  const columns: ColumnsType<(typeof state.releases)[number]> = useMemo(() => {
    const all: ColumnsType<(typeof state.releases)[number]> = [
      {
        title: 'ID',
        key: 'id',
        fixed: 'left',
        width: 110,
        render: (_, r) => <Text strong style={{ fontFamily: 'var(--mono)', fontSize: 12 }}>{r.id}</Text>,
      },
      { title: '标题', key: 'title', fixed: 'left', width: 180, render: (_, r) => <Text ellipsis style={{ maxWidth: 160 }}>{r.title}</Text> },
      { title: '环境', key: 'env', width: 90, render: (_, r) => <Tag>{r.env}</Tag> },
      { title: '状态', key: 'status', width: 100, render: (_, r) => <Tag color={TONE_ANTD[statusTone(r.status)]}>{statusLabel(r.status)}</Tag> },
      { title: '负责人', key: 'owner', width: 100, dataIndex: 'owner' },
      { title: '版本', key: 'version', width: 100, render: (_, r) => <Tag color="processing">v{r.version}</Tag> },
      { title: '最近更新', key: 'updated', width: 150, render: (_, r) => <Text type="secondary" style={{ fontSize: 11 }}>{fmtFull(r.updatedAt)}</Text> },
      { title: '备注', key: 'notes', width: 240, render: (_, r) => <Text type="secondary" style={{ fontSize: 12 }} ellipsis>{r.notes}</Text> },
    ];
    return all.filter((c) => {
      const key = ALL_COLUMNS.find((x) => x.label === c.title)?.key;
      return key ? visible.includes(key) : true;
    });
  }, [visible]);

  return (
    <PageShell
      title="表格进阶模式"
      domain="横向专项 X2"
      desc="大数据宽表的四件套：展开行 / 列显示自定义 / 密度切换 / 左右固定列；正式发布单页的升级蓝本。"
    >
      <Section
        title={
          <Space size={8} wrap>
            <span>表格</span>
            <Segmented
              size="small"
              value={density}
              onChange={(v) => setDensity(v as typeof density)}
              options={[
                { value: 'small', label: '紧凑' },
                { value: 'middle', label: '中等' },
                { value: 'large', label: '宽松' },
              ]}
              aria-label="切换表格密度"
            />
            <Dropdown
              menu={{
                items: ALL_COLUMNS.map((c) => ({
                  key: c.key,
                  label: c.label,
                })),
                selectable: true,
                multiple: true,
                defaultSelectedKeys: visible,
                onSelect: (info) => setVisible((cur) => [...cur, ...info.selectedKeys]),
                onDeselect: (info) => setVisible((cur) => cur.filter((k) => !info.selectedKeys.includes(k))),
              }}
              trigger={['click']}
            >
              <Button size="small" icon={<SettingOutlined />} aria-label="自定义显示列">
                列设置
              </Button>
            </Dropdown>
            <Tag>{visible.length} 列显示</Tag>
          </Space>
        }
      >
        <Table
          size={density}
          rowKey="id"
          columns={columns}
          dataSource={state.releases.slice(0, 10)}
          scroll={{ x: 1000 }}
          pagination={false}
          aria-label="进阶表格"
          expandable={{
            expandedRowRender: (r) => (
              <div style={{ fontSize: 12, padding: 4 }}>
                <Text strong>时间线（{r.timeline.length}）：</Text>
                {r.timeline.map((t, i) => (
                  <div key={i} style={{ marginTop: 4, color: 'var(--text-2)' }}>
                    {fmtFull(t.at)} · {t.action}（{t.by}）
                  </div>
                ))}
              </div>
            ),
            rowExpandable: (r) => r.timeline.length > 0,
          }}
        />
      </Section>
      <HintText style={{ marginTop: 12 }}>
        虚拟滚动（万行级）为成熟化方向：当前演示量级（14 条）用普通渲染即可，强行虚拟化反而增加复杂度。
      </HintText>
    </PageShell>
  );
}
