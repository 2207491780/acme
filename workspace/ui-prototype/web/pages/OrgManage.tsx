import { useMemo, useState } from 'react';
import { App, Button, Col, Input, Popconfirm, Row, Space, Tag, Tree, Typography } from 'antd';
import type { TreeDataNode } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { collectOrgKeys, ORG_TREE, type OrgNode } from '@acme/core';
import { PageShell } from '../shell/PageShell';
import { HintText, Kpi, KpiRow, Section } from '../components/blocks';

const { Text } = Typography;

function toTreeData(nodes: OrgNode[]): TreeDataNode[] {
  return nodes.map((n) => ({
    key: n.key,
    title: n.children ? n.label : `${n.label}${n.role ? `（${n.role}）` : ''}`,
    children: n.children ? toTreeData(n.children) : undefined,
  }));
}

function findNode(nodes: OrgNode[], key: string): OrgNode | null {
  for (const n of nodes) {
    if (n.key === key) return n;
    const hit = n.children ? findNode(n.children, key) : null;
    if (hit) return hit;
  }
  return null;
}

/** W3 组织架构管理：部门树 + 节点详情 + 增删（原型仅本地生效；增删已用二次确认）。 */
export default function OrgManage() {
  const { message } = App.useApp();
  const [tree, setTree] = useState<OrgNode[]>(ORG_TREE);
  const [selectedKey, setSelectedKey] = useState<string>('org-acme');
  const [newLabel, setNewLabel] = useState('');

  const selected = findNode(tree, selectedKey);
  const allKeys = useMemo(() => collectOrgKeys(tree), [tree]);
  const groupCount = allKeys.filter((k) => k.startsWith('org-')).length;
  const memberCount = allKeys.filter((k) => k.startsWith('u-')).length;

  const removeNode = (nodes: OrgNode[], key: string): OrgNode[] =>
    nodes
      .filter((n) => n.key !== key)
      .map((n) => (n.children ? { ...n, children: removeNode(n.children, key) } : n));

  const addChild = (nodes: OrgNode[], parentKey: string, label: string): OrgNode[] =>
    nodes.map((n) => {
      if (n.key === parentKey) {
        return { ...n, children: [...(n.children ?? []), { key: `org-new-${Date.now()}`, label }] };
      }
      return n.children ? { ...n, children: addChild(n.children, parentKey, label) } : n;
    });

  return (
    <PageShell
      title="组织架构管理"
      domain="内容与治理域 W3"
      desc="部门/产品线树形管理：节点增删与归属调整；与 D1 成员、U2 详情共享成员口径。"
    >
      <KpiRow>
        <Kpi label="分组节点" value={groupCount} tone="info" />
        <Kpi label="成员节点" value={memberCount} tone="violet" />
        <Kpi label="树深度" value={3} tone="muted" />
      </KpiRow>

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={14}>
          <Section title="组织树（点选节点查看详情）">
            <Tree
              blockNode
              defaultExpandAll
              showIcon
              treeData={toTreeData(tree)}
              selectedKeys={[selectedKey]}
              onSelect={(keys) => keys[0] && setSelectedKey(String(keys[0]))}
              aria-label="组织架构树"
            />
          </Section>
        </Col>
        <Col xs={24} lg={10}>
          <Section title={`节点详情 · ${selected?.label ?? '—'}`}>
            {selected ? (
              <>
                <Space size={6} wrap style={{ marginBottom: 10 }}>
                  {selected.children ? <Tag color="processing">分组</Tag> : <Tag color="success">成员</Tag>}
                  {selected.role && <Tag>{selected.role}</Tag>}
                </Space>
                {selected.children && (
                  <div style={{ marginBottom: 12 }}>
                    <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 6 }}>直属子节点：{selected.children.length}</Text>
                    <Space size={4} wrap>
                      {selected.children.map((c) => (
                        <Tag key={c.key} style={{ cursor: 'pointer' }} onClick={() => setSelectedKey(c.key)}>{c.label}</Tag>
                      ))}
                    </Space>
                  </div>
                )}
                <Space direction="vertical" size={10} style={{ width: '100%' }}>
                  <Space.Compact style={{ width: '100%' }}>
                    <Input value={newLabel} onChange={(e) => setNewLabel(e.target.value)} placeholder="新子节点名称" aria-label="新子节点名称" />
                    <Button
                      icon={<PlusOutlined />}
                      disabled={!newLabel.trim()}
                      onClick={() => {
                        setTree((cur) => addChild(cur, selectedKey, newLabel.trim()));
                        setNewLabel('');
                        message.success('原型演示：已添加子节点（仅本地生效）');
                      }}
                    >
                      添加子节点
                    </Button>
                  </Space.Compact>
                  <Popconfirm
                    title="确认删除该节点？"
                    description={selected.children?.length ? '该节点包含子节点，将一并移除。' : '叶子节点将直接移除。'}
                    okText="删除"
                    cancelText="取消"
                    okButtonProps={{ danger: true }}
                    onConfirm={() => {
                      if (selectedKey === 'org-acme') {
                        message.error('根节点不可删除');
                        return;
                      }
                      setTree((cur) => removeNode(cur, selectedKey));
                      setSelectedKey('org-acme');
                      message.warning('原型演示：节点已删除（仅本地生效）');
                    }}
                  >
                    <Button danger disabled={selectedKey === 'org-acme'}>
                      删除节点
                    </Button>
                  </Popconfirm>
                </Space>
                <HintText style={{ marginTop: 10 }}>危险操作已按 F7 规格接 Popconfirm 二次确认。</HintText>
              </>
            ) : (
              <HintText>选择左侧节点查看详情。</HintText>
            )}
          </Section>
        </Col>
      </Row>
    </PageShell>
  );
}
