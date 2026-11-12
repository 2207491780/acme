import { useState } from 'react';
import {
  App,
  Avatar,
  Breadcrumb,
  Button,
  Cascader,
  Card,
  DatePicker,
  Dropdown,
  Popconfirm,
  Popover,
  Space,
  Tabs,
  TimePicker,
  Tree,
  Typography,
  type TreeDataNode,
} from 'antd';
import {
  AuditOutlined,
  DownOutlined,
  ThunderboltOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { ORG_TREE, TRANSFER_POOL, cascaderOptions } from '@acme/core';
import { useStore } from '../../store/useStore';
import { PageShell } from '../shell/PageShell';
import { HintText, Section } from '../components/blocks';

const { Text } = Typography;

const treeData: TreeDataNode[] = ORG_TREE.map((n) => ({
  key: n.key,
  title: n.label,
  children: n.children?.map((group) => ({
    key: group.key,
    title: group.label,
    children: group.children?.map((leaf) => ({ key: leaf.key, title: `${leaf.label}（${leaf.role}）` })),
  })),
}));

const DAY_FORMAT = 'YYYY-MM-DD';

function PickedChip({ children }: { children: React.ReactNode }) {
  return (
    <span style={{ display: 'inline-block', padding: '2px 8px', borderRadius: 6, background: 'var(--surface-3)', fontSize: 12 }}>
      {children}
    </span>
  );
}

function PermissionAssigner({
  pool,
  target,
  onChange,
}: {
  pool: Array<{ key: string; label: string }>;
  target: string[];
  onChange: (v: string[]) => void;
}) {
  return (
    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
      {pool.map((item) => {
        const on = target.includes(item.key);
        return (
          <Button
            key={item.key}
            size="small"
            type={on ? 'primary' : 'default'}
            onClick={() => onChange(on ? target.filter((k) => k !== item.key) : [...target, item.key])}
            aria-label={`${item.label}（${on ? '已分配' : '未分配'}）`}
          >
            {item.label}
          </Button>
        );
      })}
    </div>
  );
}

/** F7 通用组件样例库：补齐管理端高频通用件（每件带用例与使用规格），样式全部走既有令牌。 */
export default function ComponentGallery() {
  const { message } = App.useApp();
  const state = useStore((s) => s.state);
  const navigate = useNavigate();
  const [checkedKeys, setCheckedKeys] = useState<string[]>(['u-he']);
  const [transferTarget, setTransferTarget] = useState<string[]>(['perm-publish-web']);
  const [cascadeValue, setCascadeValue] = useState<string[]>([]);
  const [pickedDate, setPickedDate] = useState<string | null>(null);

  const cascadeOpts = cascaderOptions(state);

  return (
    <PageShell
      title="通用组件样例库"
      domain="横向专项 F7"
      desc="补齐管理端高频通用件的用例与规格；本页组件即成熟化时的迁移蓝本（样式走既有令牌）。"
    >
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
        <Section title="① 用户菜单（Dropdown + Avatar）—— 顶栏标配">
          <Space size={14} align="center">
            <Dropdown
              menu={{
                items: [
                  { key: 'desk', icon: <ThunderboltOutlined />, label: '我的工作台（U3）' },
                  { key: 'profile', icon: <UserOutlined />, label: '个人详情（U2）' },
                  { key: 'audit', icon: <AuditOutlined />, label: '我的操作留痕' },
                  { type: 'divider' },
                  { key: 'logout', label: '退出登录（演示）', danger: true },
                ],
                onClick: ({ key }) => {
                  if (key === 'desk') navigate('/prototype/my-desk');
                  else if (key === 'profile') navigate(`/prototype/user-detail?user=${encodeURIComponent('何青')}`);
                  else if (key === 'audit') navigate('/prototype/audit');
                  else message.info('原型演示：正式应用无登录态（见 ADR 04）');
                },
              }}
            >
              <Button aria-label="打开用户菜单">
                <Space size={6}>
                  <Avatar size={22} style={{ background: 'var(--accent)' }} icon={<UserOutlined />} />
                  何青 <DownOutlined style={{ fontSize: 10 }} />
                </Space>
              </Button>
            </Dropdown>
            <Text type="secondary" style={{ fontSize: 12 }}>↑ 同款已接入正式顶栏</Text>
          </Space>
          <HintText style={{ marginTop: 10 }}>规格：头像+姓名触发；菜单含工作台/详情/留痕三类出口，危险项红色置底。</HintText>
        </Section>

        <Section title="② 页内 Tabs 页签 —— 多区块信息分组">
          <Tabs
            defaultActiveKey="1"
            items={[
              { key: '1', label: '审批待办', children: <Text style={{ fontSize: 12.5 }}>当前身份 2 个待办（联动 U3）。</Text> },
              { key: '2', label: '签署记录', children: <Text style={{ fontSize: 12.5 }}>历史签核列表（联动 A1 口径）。</Text> },
              { key: '3', label: '操作留痕', children: <Text style={{ fontSize: 12.5 }}>全量动作流（联动 C3）。</Text> },
            ]}
          />
          <HintText>规格：tab ≤ 5 个用 Tabs；U2 详情页已按此改造（见该页审批区）。</HintText>
        </Section>

        <Section title="③ 日期 / 时间选择（DatePicker / RangePicker / TimePicker）">
          <Space direction="vertical" size={10} style={{ width: '100%' }}>
            <DatePicker
              style={{ width: '100%' }}
              placeholder="选择发布窗口日期"
              onChange={(d) => setPickedDate(d ? d.format(DAY_FORMAT) : null)}
              aria-label="选择发布窗口日期"
            />
            <Space wrap>
              <DatePicker.RangePicker style={{ width: 260 }} aria-label="选择统计区间" />
              <TimePicker style={{ width: 120 }} minuteStep={30} format="HH:mm" placeholder="窗口开始" aria-label="选择窗口开始时间" />
            </Space>
            {pickedDate && <PickedChip>{pickedDate}</PickedChip>}
          </Space>
          <HintText style={{ marginTop: 8 }}>规格：prod 冻结期（22:00-08:00）内的选择需即时提示；A4 日历已接 DatePicker 按日过滤。</HintText>
        </Section>

        <Section title="④ Popconfirm 危险操作二次确认">
          <Space size={12} wrap>
            <Popconfirm
              title="确认删除该发布单？"
              description="删除后可在 5 秒内撤销（走 RESTORE 口径）。"
              okText="删除"
              cancelText="取消"
              okButtonProps={{ danger: true }}
              onConfirm={() => message.success('原型演示：已删除（可撤销）')}
            >
              <Button danger>删除发布单</Button>
            </Popconfirm>
            <Popconfirm
              title="回滚到 v1.4.2？"
              description="高风险操作，需双人复核（R1 口径）。"
              okText="确认回滚"
              cancelText="取消"
              onConfirm={() => message.warning('原型演示：回滚仅本地生效')}
            >
              <Button>回滚发布</Button>
            </Popconfirm>
          </Space>
          <HintText style={{ marginTop: 10 }}>规格：可逆操作用「通知+撤销」；不可逆/高风险用 Popconfirm 并注明后果。</HintText>
        </Section>

        <Section title="⑤ 面包屑 Breadcrumb —— 层级导航">
          <Breadcrumb
            items={[
              { title: '管理端' },
              { title: '用户域', onClick: () => navigate('/prototype/users') },
              { title: '何青', onClick: () => navigate('/prototype/user-detail?user=%E4%BD%95%E9%9D%92') },
              { title: '审批记录' },
            ]}
          />
          <HintText style={{ marginTop: 10 }}>规格：层级 ≥ 3 的详情页显示；上级可点击；末级为当前页不可点。</HintText>
        </Section>

        <Section title="⑥ Tree 树形控件 —— 组织架构">
          <Tree
            checkable
            defaultExpandAll
            checkedKeys={checkedKeys}
            onCheck={(keys) => setCheckedKeys(keys as string[])}
            treeData={treeData}
            aria-label="组织架构树"
          />
          <Text type="secondary" style={{ fontSize: 12 }}>已选 {checkedKeys.length} 个节点（演示：勾选=加入审批池）</Text>
          <HintText style={{ marginTop: 8 }}>规格：组织/权限/应用分组用树；叶子挂角色标签；勾选态需联动计数。</HintText>
        </Section>

        <Section title="⑦ Popover 悬浮卡片 —— 行内预览">
          <Space size={12} wrap>
            <Popover
              title="REL-2412 · 登录页视觉升级"
              content={
                <div style={{ fontSize: 12, maxWidth: 220 }}>
                  v1.4.3 → test · 林晚<br />测试中（进度 62%）· 产物 8.6 MB<br />点击行进详情（联动 A3）
                </div>
              }
              trigger="hover"
            >
              <Button type="dashed">悬停我：发布单摘要卡</Button>
            </Popover>
          </Space>
          <HintText style={{ marginTop: 10 }}>规格：表格行/链接 hover 预览摘要；卡内放跳转入口，避免塞满全部字段。</HintText>
        </Section>

        <Section title="⑧ Cascader 级联 + Transfer 式分配（高级选择器）">
          <Space direction="vertical" size={10} style={{ width: '100%' }}>
            <div>
              <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 4 }}>级联：环境 → 应用 → 版本（store 推导）</Text>
              <Cascader
                style={{ width: '100%' }}
                options={cascadeOpts}
                value={cascadeValue}
                onChange={(v) => setCascadeValue(v as string[])}
                placeholder="选择 环境 / 应用 / 版本"
                aria-label="级联选择环境应用版本"
              />
              {cascadeValue.length > 0 && <PickedChip>{cascadeValue.join(' / ')}</PickedChip>}
            </div>
            <div>
              <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 4 }}>
                穿梭式分配：把权限分给「何青」（{transferTarget.length}/{TRANSFER_POOL.length}）
              </Text>
              <PermissionAssigner pool={TRANSFER_POOL} target={transferTarget} onChange={setTransferTarget} />
            </div>
          </Space>
          <HintText style={{ marginTop: 10 }}>
            规格：级联用于三层强结构；少量成员/权限互选用本例按钮组（&gt;20 项改 antd Transfer / 多选 Select 分组）。
          </HintText>
        </Section>
      </div>

      <Card size="small" style={{ marginTop: 16 }}>
        <HintText>
          F7 清单闭环：8 个缺口组件全部给出可运行用例。正式顶栏用户菜单、U2 Tabs、A4 DatePicker 已同步接线（见对应页面与顶栏右上角）。
        </HintText>
      </Card>
    </PageShell>
  );
}
