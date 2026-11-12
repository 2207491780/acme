import { useMemo, useState } from 'react';
import { Modal, Text, View } from 'react-native';
import { cascaderOptions, ORG_TREE, TRANSFER_POOL, type OrgNode } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Button, Muted } from '../../components/ui';
import { useAccent } from '../../hooks';
import { Chip, MonoText, ProtoShell, Section, useFlash } from './kit';
import { palette, TONE_COLOR } from '../../theme';

type OrgNode_ = OrgNode;

function TreeView({ nodes, depth = 0, checked, onToggle }: {
  nodes: OrgNode_[];
  depth?: number;
  checked: string[];
  onToggle: (key: string) => void;
}) {
  const accent = useAccent();
  return (
    <View style={{ marginLeft: depth * 16, gap: 6 }}>
      {nodes.map((n) => {
        const isLeaf = !n.children;
        const on = checked.includes(n.key);
        return (
          <View key={n.key}>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
              {isLeaf ? (
                <View
                  style={{
                    width: 16,
                    height: 16,
                    borderRadius: 4,
                    borderWidth: 1.5,
                    borderColor: on ? accent : palette.border,
                    backgroundColor: on ? accent : 'transparent',
                    alignItems: 'center',
                    justifyContent: 'center',
                  }}
                >
                  {on ? <Text style={{ color: TONE_COLOR.success, fontSize: 10 }}>✓</Text> : null}
                </View>
              ) : (
                <Text style={{ color: palette.muted, fontSize: 12 }}>▾</Text>
              )}
              <Text
                onPress={isLeaf ? () => onToggle(n.key) : undefined}
                style={{ color: isLeaf ? (on ? accent : palette.text2) : palette.text, fontSize: 13, fontWeight: isLeaf ? '400' : '700' }}
              >
                {n.label}
                {n.role ? `（${n.role}）` : ''}
              </Text>
            </View>
            {n.children ? <TreeView nodes={n.children} depth={depth + 1} checked={checked} onToggle={onToggle} /> : null}
          </View>
        );
      })}
    </View>
  );
}

/** F7 通用组件样例库（APP 形态）：页内页签/确认弹窗/树形/穿梭式分配/日期 chip 选择。 */
export default function ComponentGalleryScreen({ onBack }: { onBack: () => void }) {
  const state = useStore((s) => s.state);
  const [flash, showFlash] = useFlash();
  const [tab, setTab] = useState<'todo' | 'signed'>('todo');
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [checked, setChecked] = useState<string[]>(['u-he']);
  const [transferTarget, setTransferTarget] = useState<string[]>(['perm-publish-web']);
  const [menuOpen, setMenuOpen] = useState(false);
  const [cascadeEnv, setCascadeEnv] = useState<'dev' | 'test' | 'prod'>('prod');
  const cascade = useMemo(() => cascaderOptions(state).find((o) => o.value === cascadeEnv)!, [state, cascadeEnv]);

  return (
    <ProtoShell
      title="通用组件样例库"
      domain="横向专项 F7"
      desc="RN 等价形态：页签/确认弹窗/树/穿梭分配/级联 chip；日期滚轮成熟化时登记 datetimepicker。"
      onBack={onBack}
      flash={flash}
    >
      <Section title="① 用户菜单（模态形态）">
        <Button label="打开用户菜单" onPress={() => setMenuOpen(true)} />
        <MonoText color={palette.muted}>Web 端为顶栏 Dropdown；RN 无该组件，以模态菜单等价实现。</MonoText>
      </Section>

      <Section title="② 页内页签（Tabs）">
        <View style={{ flexDirection: 'row', gap: 8, marginBottom: 10 }}>
          {(['todo', 'signed'] as const).map((k) => (
            <Chip key={k} label={k === 'todo' ? '审批待办' : '签署记录'} active={tab === k} onPress={() => setTab(k)} />
          ))}
        </View>
        <Text style={{ color: palette.text2, fontSize: 12.5 }}>
          {tab === 'todo' ? '当前身份 2 个待办（联动 U3）。' : '历史签核列表（联动 A1 口径）。'}
        </Text>
      </Section>

      <Section title="③ 危险操作确认（Popconfirm 等价）">
        <Button label="删除发布单（需确认）" variant="danger" onPress={() => setConfirmOpen(true)} />
        <MonoText color={palette.muted}>可逆操作用通知+撤销；不可逆操作必须二次确认。</MonoText>
      </Section>

      <Section title="④ 树形控件（组织架构）">
        <TreeView nodes={ORG_TREE} checked={checked} onToggle={(key) => setChecked((cur) => (cur.includes(key) ? cur.filter((x) => x !== key) : [...cur, key]))} />
        <MonoText color={palette.text}>已选 {checked.length} 个节点</MonoText>
      </Section>

      <Section title="⑤ 穿梭式分配（权限）">
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
          {TRANSFER_POOL.map((item) => {
            const on = transferTarget.includes(item.key);
            return (
              <Chip
                key={item.key}
                label={item.label}
                active={on}
                onPress={() => setTransferTarget((cur) => (on ? cur.filter((k) => k !== item.key) : [...cur, item.key]))}
              />
            );
          })}
        </View>
        <MonoText color={palette.text}>已分配 {transferTarget.length}/{TRANSFER_POOL.length}</MonoText>
      </Section>

      <Section title="⑥ 级联选择（环境 → 应用 → 版本）">
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginBottom: 8 }}>
          {(['dev', 'test', 'prod'] as const).map((e) => (
            <Chip key={e} label={e} active={cascadeEnv === e} onPress={() => setCascadeEnv(e)} />
          ))}
        </View>
        <View style={{ gap: 4 }}>
          {cascade.children?.map((appNode) => (
            <View key={appNode.value}>
              <Text style={{ color: palette.text, fontSize: 12.5, fontWeight: '700' }}>{appNode.label}</Text>
              <MonoText color={palette.muted}>
                {(appNode.children ?? []).map((leaf) => leaf.label).join('、') || '该环境暂无已发布版本'}
              </MonoText>
            </View>
          ))}
        </View>
      </Section>

      <Modal visible={confirmOpen} transparent animationType="fade" onRequestClose={() => setConfirmOpen(false)}>
        <View style={styles.overlay}>
          <View style={styles.sheet}>
            <Text style={{ color: palette.text, fontSize: 15, fontWeight: '800' }}>确认删除该发布单？</Text>
            <Muted>删除后可在 5 秒内撤销（RESTORE 口径）。</Muted>
            <View style={{ flexDirection: 'row', gap: 10, marginTop: 12 }}>
              <Button label="取消" variant="ghost" onPress={() => setConfirmOpen(false)} />
              <Button
                label="删除"
                variant="danger"
                onPress={() => {
                  setConfirmOpen(false);
                  showFlash('原型演示：已删除（可撤销）');
                }}
              />
            </View>
          </View>
        </View>
      </Modal>

      <Modal visible={menuOpen} transparent animationType="fade" onRequestClose={() => setMenuOpen(false)}>
        <View style={styles.overlay}>
          <View style={styles.sheet}>
            <Text style={{ color: palette.text, fontSize: 15, fontWeight: '800' }}>用户菜单</Text>
            <Button label="我的工作台（U3）" onPress={() => { setMenuOpen(false); showFlash('原型演示：跳转 U3（返回索引后选择）'); }} />
            <Button label="个人详情（U2）" variant="ghost" onPress={() => { setMenuOpen(false); showFlash('原型演示：经 U1 列表点击成员进入'); }} />
            <Button label="退出登录（演示）" variant="danger" onPress={() => { setMenuOpen(false); showFlash('原型演示：无登录态（ADR 04）'); }} />
          </View>
        </View>
      </Modal>
    </ProtoShell>
  );
}

const styles = {
  overlay: { flex: 1, backgroundColor: 'rgba(0,0,0,0.4)', justifyContent: 'flex-end' as const },
  sheet: { backgroundColor: palette.surface, padding: 16, gap: 10, borderTopLeftRadius: 16, borderTopRightRadius: 16 },
};
