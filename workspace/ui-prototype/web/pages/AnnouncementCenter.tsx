import { useMemo, useState } from 'react';
import { App, Button, Card, Col, Form, Input, Modal, Row, Space, Tag, Typography } from 'antd';
import { NotificationOutlined, PlusOutlined } from '@ant-design/icons';
import { announcements, docCards, fmtDate, type Announcement } from '@acme/core';
import { PageShell } from '../shell/PageShell';
import { HintText, Kpi, KpiRow, Section } from '../components/blocks';
import { EmptyState } from '../components/states';

const { Text } = Typography;

/** W1 公告与规范中心：团队公告（置顶优先）+ 规范/指南/复盘文档；公告编辑为 Markdown 原型。 */
export default function AnnouncementCenter() {
  const { message } = App.useApp();
  const now = Date.now();
  const base = useMemo(() => announcements(now), [now]);
  const docs = useMemo(() => docCards(now), [now]);
  const [extra, setExtra] = useState<Announcement[]>([]);
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm<{ title: string; body: string }>();

  const list = [...extra, ...base];
  const pinned = list.filter((a) => a.pinned);

  const publish = (v: { title: string; body: string }) => {
    setExtra((cur) => [
      { id: `local-${Date.now()}`, title: v.title, body: v.body, author: '我', at: new Date().toISOString(), pinned: false, tone: 'info' as const },
      ...cur,
    ]);
    setOpen(false);
    form.resetFields();
    message.success('原型演示：公告已发布（仅本地生效）');
  };

  return (
    <PageShell
      title="公告与规范中心"
      domain="内容与治理域 W1"
      desc="团队公告（置顶优先）与规范/指南/复盘文档；公告编辑为 Markdown 形态原型。"
      extra={
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>
          发布公告
        </Button>
      }
    >
      <KpiRow>
        <Kpi label="公告总数" value={list.length} tone="info" />
        <Kpi label="置顶" value={pinned.length} tone="warning" />
        <Kpi label="文档" value={docs.length} tone="violet" />
        <Kpi label="本周更新" value={docs.filter((d) => now - new Date(d.updatedAt).getTime() < 7 * 86400_000).length} tone="success" />
      </KpiRow>

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={14}>
          <Section title={`公告（${list.length}）`}>
            {list.length === 0 ? (
              <EmptyState title="暂无公告" desc="点击右上角发布公告。" />
            ) : (
              <Space direction="vertical" size={10} style={{ width: '100%' }}>
                {list.map((a: Announcement) => (
                  <Card key={a.id} size="small" style={a.pinned ? { borderColor: 'var(--amber)' } : undefined}>
                    <Space size={6} wrap style={{ marginBottom: 6 }}>
                      {a.pinned && <Tag color="warning">置顶</Tag>}
                      <Text strong>{a.title}</Text>
                      <Tag>{a.tone === 'info' ? '通知' : a.tone === 'warning' ? '纪律' : '一般'}</Tag>
                    </Space>
                    <div style={{ fontSize: 12.5, color: 'var(--text-2)', whiteSpace: 'pre-wrap', lineHeight: 1.7 }}>
                      {a.body}
                    </div>
                    <div style={{ fontSize: 11, color: 'var(--muted)', marginTop: 6 }}>
                      {a.author} · {fmtDate(a.at)}
                    </div>
                  </Card>
                ))}
              </Space>
            )}
          </Section>
        </Col>
        <Col xs={24} lg={10}>
          <Section title={`规范与文档（${docs.length}）`}>
            {docs.map((d) => (
              <div key={d.id} style={{ display: 'flex', gap: 8, alignItems: 'center', padding: '8px 0', borderBottom: '1px solid var(--border)' }}>
                <NotificationOutlined style={{ color: 'var(--accent)', fontSize: 14 }} />
                <Text style={{ fontSize: 13, flex: 1 }}>{d.title}</Text>
                <Tag color="processing">{d.kind}</Tag>
                <Text type="secondary" style={{ fontSize: 11 }}>{fmtDate(d.updatedAt)}</Text>
              </div>
            ))}
            <HintText style={{ marginTop: 8 }}>文档内容管理为成熟化方向；当前原型仅展示索引卡片。</HintText>
          </Section>
          <Card size="small" style={{ marginTop: 16 }}>
            <Text strong style={{ fontSize: 13 }}>Markdown 编辑器说明</Text>
            <div style={{ fontSize: 12, color: 'var(--text-2)', marginTop: 6, lineHeight: 1.7 }}>
              「发布公告」弹窗为 Markdown 原型：正文支持 <code>**加粗**</code> 与换行分段（原型按纯文本渲染）。
              成熟化时引入分屏预览，需先登记 markdown 渲染依赖。
            </div>
          </Card>
        </Col>
      </Row>

      <Modal
        title="发布公告"
        open={open}
        onCancel={() => setOpen(false)}
        onOk={() => form.submit()}
        okText="发布"
        cancelText="取消"
        width={560}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" onFinish={publish} requiredMark>
          <Form.Item name="title" label="标题" rules={[{ required: true, message: '请输入标题' }, { max: 40 }]}>
            <Input placeholder="公告标题" maxLength={40} showCount />
          </Form.Item>
          <Form.Item name="body" label="正文（支持 Markdown 语法）" rules={[{ required: true, message: '请输入正文' }]}>
            <Input.TextArea rows={6} placeholder={'**重点** 可用加粗标注\n换行分段…'} />
          </Form.Item>
        </Form>
      </Modal>
    </PageShell>
  );
}
