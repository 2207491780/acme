import { useMemo, useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import { collectOrgKeys, ORG_TREE, type OrgNode } from '@acme/core';
import { Button, Muted, Tag } from '../../components/ui';
import { Chip, Kpi, KpiRow, ProtoShell, Section, useFlash } from './kit';
import { palette, TONE_COLOR } from '../../theme';
import { useAccent } from '../../hooks';

function findNode(nodes: OrgNode[], key: string): OrgNode | null {
  for (const n of nodes) {
    if (n.key === key) return n;
    const hit = n.children ? findNode(n.children, key) : null;
    if (hit) return hit;
  }
  return null;
}

function removeNode(nodes: OrgNode[], key: string): OrgNode[] {
  return nodes
    .filter((n) => n.key !== key)
    .map((n) => (n.children ? { ...n, children: removeNode(n.children, key) } : n));
}

function addChild(nodes: OrgNode[], parentKey: string, label: string): OrgNode[] {
  return nodes.map((n) => {
    if (n.key === parentKey) {
      return { ...n, children: [...(n.children ?? []), { key: `org-new-${Date.now()}`, label }] };
    }
    return n.children ? { ...n, children: addChild(n.children, parentKey, label) } : n;
  });
}

function TreeList({ nodes, depth, selectedKey, onSelect }: {
  nodes: OrgNode[];
  depth: number;
  selectedKey: string;
  onSelect: (key: string) => void;
}) {
  const accent = useAccent();
  return (
    <View style={{ marginLeft: depth * 14, gap: 6 }}>
      {nodes.map((n) => (
        <View key={n.key}>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={`选择节点 ${n.label}`}
            onPress={() => onSelect(n.key)}
            style={{ flexDirection: 'row', alignItems: 'center', gap: 8, paddingVertical: 5 }}
          >
            <View style={{ width: 6, height: 6, borderRadius: 99, backgroundColor: selectedKey === n.key ? accent : palette.border }} />
            <Text style={{ color: selectedKey === n.key ? accent : palette.text2, fontSize: 13, fontWeight: selectedKey === n.key ? '700' : '400' }}>
              {n.label}{n.role ? `（${n.role}）` : ''}
            </Text>
          </Pressable>
          {n.children ? <TreeList nodes={n.children} depth={depth + 1} selectedKey={selectedKey} onSelect={onSelect} /> : null}
        </View>
      ))}
    </View>
  );
}

/** W3 组织架构管理（APP 形态）：树选 + 节点增删（Popconfirm 式二次确认走 flash 提示口径）。 */
export default function OrgManageScreen({ onBack }: { onBack: () => void }) {
  const [tree, setTree] = useState<OrgNode[]>(ORG_TREE);
  const [selectedKey, setSelectedKey] = useState('org-acme');
  const [flash, showFlash] = useFlash();
  const selected = findNode(tree, selectedKey);
  const keys = useMemo(() => collectOrgKeys(tree), [tree]);
  const memberCount = keys.filter((k) => k.startsWith('u-')).length;

  return (
    <ProtoShell
      title="组织架构管理"
      domain="内容与治理域 W3"
      desc="部门树增删与归属调整（仅本地生效）。"
      onBack={onBack}
      flash={flash}
    >
      <KpiRow>
        <Kpi label="分组节点" value={keys.filter((k) => k.startsWith('org-')).length} tone="info" />
        <Kpi label="成员节点" value={memberCount} tone="violet" />
      </KpiRow>

      <Section title="组织树（点选节点）">
        <TreeList nodes={tree} depth={0} selectedKey={selectedKey} onSelect={setSelectedKey} />
      </Section>

      {selected ? (
        <Section title={`节点详情 · ${selected.label}`}>
          <View style={{ flexDirection: 'row', gap: 6, alignItems: 'center', marginBottom: 8, flexWrap: 'wrap' }}>
            <Tag label={selected.children ? '分组' : '成员'} color={selected.children ? TONE_COLOR.info : TONE_COLOR.success} />
            {selected.role ? <Tag label={selected.role} color={TONE_COLOR.muted} /> : null}
          </View>
          {selected.children ? (
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 6, marginBottom: 10 }}>
              {selected.children.map((c) => (
                <Chip key={c.key} label={c.label} active={false} onPress={() => setSelectedKey(c.key)} />
              ))}
            </View>
          ) : null}
          <View style={{ flexDirection: 'row', gap: 10, flexWrap: 'wrap' }}>
            <Button
              label="添加子节点"
              variant="ghost"
              small
              onPress={() => {
                setTree((cur) => addChild(cur, selectedKey, `新节点 ${new Date().toLocaleTimeString('zh-CN', { minute: '2-digit', second: '2-digit' })}`));
                showFlash('原型演示：已添加子节点');
              }}
            />
            <Button
              label="删除节点"
              variant="danger"
              small
              disabled={selectedKey === 'org-acme'}
              onPress={() => {
                if (selected.children?.length) {
                  showFlash('该节点包含子节点，需先清空（W3 纪律）');
                  return;
                }
                setTree((cur) => removeNode(cur, selectedKey));
                setSelectedKey('org-acme');
                showFlash('原型演示：节点已删除');
              }}
            />
          </View>
        </Section>
      ) : (
        <Muted>选择节点查看详情。</Muted>
      )}
    </ProtoShell>
  );
}
