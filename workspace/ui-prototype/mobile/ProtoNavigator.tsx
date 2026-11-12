import { useState } from 'react';
import type { ComponentType } from 'react';
import { Modal, Pressable, Text, View, StyleSheet } from 'react-native';
import { useStore } from '../../store/useStore';
import { Button, Muted, Tag } from '../../components/ui';
import { useAccent } from '../../hooks';
import { palette } from '../../theme';
import { useFlash } from './kit';
import ApprovalScreen from './ApprovalScreen';
import BuildLogScreen from './BuildLogScreen';
import ReleaseDetailScreen from './ReleaseDetailScreen';
import CalendarScreen from './CalendarScreen';
import RollbackScreen from './RollbackScreen';
import OrchestrationScreen from './OrchestrationScreen';
import FlowConfigScreen from './FlowConfigScreen';
import TemplatesScreen from './TemplatesScreen';
import PromotionScreen from './PromotionScreen';
import ReportsScreen from './ReportsScreen';
import LiveBoardScreen from './LiveBoardScreen';
import AuditScreen from './AuditScreen';
import MembersScreen from './MembersScreen';
import NotificationsScreen from './NotificationsScreen';
import CommentsScreen from './CommentsScreen';
import AppDetailScreen from './AppDetailScreen';
import NewAppScreen from './NewAppScreen';
import SettingsExtScreen from './SettingsExtScreen';
import StatesScreen from './StatesScreen';
import GesturesScreen from './GesturesScreen';
import WallScreen from './WallScreen';
import PipelineScreen from './PipelineScreen';
import HeatmapScreen from './HeatmapScreen';
import FunnelScreen from './FunnelScreen';
import RadarScreen from './RadarScreen';
import GanttScreen from './GanttScreen';
import NodeWallScreen from './NodeWallScreen';
import UserDirectoryScreen from './UserDirectoryScreen';
import MyDeskScreen from './MyDeskScreen';
import ReadinessScreen from './ReadinessScreen';
import CreateWizardScreen from './CreateWizardScreen';
import ArtifactScreen from './ArtifactScreen';
import DiffScreen from './DiffScreen';
import EnvScreen from './EnvScreen';
import TopologyScreen from './TopologyScreen';
import IncidentScreen from './IncidentScreen';
import GrayScreen from './GrayScreen';
import IntegrationScreen from './IntegrationScreen';
import SearchScreen from './SearchScreen';
import ComponentGalleryScreen from './ComponentGalleryScreen';

interface ProtoEntry {
  id: string;
  title: string;
  domain: string;
  desc: string;
  Screen: ComponentType<{ onBack: () => void }>;
}

const DOMAINS = ['发布单域', '发布工程域', '流程配置域', '观测域', '可视化看板', '协作域', '用户域', '环境域', '稳定性域', '集成域', '应用与配置域', '横向专项'] as const;

const REGISTRY: ProtoEntry[] = [
  { id: 'approval', title: '审批详情（A1）', domain: '发布单域', desc: '审批链与通过/驳回', Screen: ApprovalScreen },
  { id: 'build-log', title: '构建日志（A2）', domain: '发布单域', desc: '级别过滤的日志流', Screen: BuildLogScreen },
  { id: 'release-detail', title: '发布单详情（A3）', domain: '发布单域', desc: '产物 / 时间线 / 关联', Screen: ReleaseDetailScreen },
  { id: 'calendar', title: '发布日历（A4）', domain: '发布单域', desc: '排期窗口与冲突', Screen: CalendarScreen },
  { id: 'rollback', title: '回滚向导（A5）', domain: '发布单域', desc: '四步安全回滚', Screen: RollbackScreen },
  { id: 'orchestration', title: '批量编排（A6）', domain: '发布单域', desc: '依赖拓扑分批', Screen: OrchestrationScreen },
  { id: 'flow-config', title: '审批流配置（B1）', domain: '流程配置域', desc: '按环境的审批规则', Screen: FlowConfigScreen },
  { id: 'templates', title: '流水线模板（B2）', domain: '流程配置域', desc: '阶段编排模板', Screen: TemplatesScreen },
  { id: 'promotion', title: '环境晋升看板（B3）', domain: '流程配置域', desc: 'dev → test → prod', Screen: PromotionScreen },
  { id: 'reports', title: '数据报表（C1）', domain: '观测域', desc: '趋势 / 归因 / 耗时', Screen: ReportsScreen },
  { id: 'live-board', title: '实时大盘（C2）', domain: '观测域', desc: '流水线与队列', Screen: LiveBoardScreen },
  { id: 'audit', title: '活动审计（C3）', domain: '观测域', desc: '操作留痕流', Screen: AuditScreen },
  { id: 'members', title: '成员与角色（D1）', domain: '协作域', desc: '成员卡与权限矩阵', Screen: MembersScreen },
  { id: 'notifications', title: '通知中心（D2）', domain: '协作域', desc: '分类收件箱', Screen: NotificationsScreen },
  { id: 'comments', title: '发布单评论（D3）', domain: '协作域', desc: '讨论与 @ 提及', Screen: CommentsScreen },
  { id: 'app-detail', title: '应用详情（E1）', domain: '应用与配置域', desc: '健康分布与环境变量', Screen: AppDetailScreen },
  { id: 'new-app', title: '新建应用向导（E2）', domain: '应用与配置域', desc: '三步创建应用', Screen: NewAppScreen },
  { id: 'settings-ext', title: '设置扩展（E3）', domain: '应用与配置域', desc: '主题 / 快捷键 / 多语言', Screen: SettingsExtScreen },
  { id: 'states', title: '全局状态规范（F1）', domain: '横向专项', desc: '四态统一规格', Screen: StatesScreen },
  { id: 'gestures', title: '手势深化（F4）', domain: '横向专项', desc: '滑动操作与下拉刷新', Screen: GesturesScreen },
  // 可视化看板 V（增量二）
  { id: 'wall', title: '数据大屏（V0）', domain: '可视化看板', desc: '趋势/漏斗/热力/节点总览', Screen: WallScreen },
  { id: 'pipeline', title: '阶段管道图（V1）', domain: '可视化看板', desc: '发布单阶段链路', Screen: PipelineScreen },
  { id: 'heatmap', title: '发布热力图（V2）', domain: '可视化看板', desc: '按日活动强度格', Screen: HeatmapScreen },
  { id: 'funnel', title: '发布漏斗（V3）', domain: '可视化看板', desc: '在途留存与转化率', Screen: FunnelScreen },
  { id: 'radar', title: '应用健康雷达（V4）', domain: '可视化看板', desc: '五维评分对比（条形近似）', Screen: RadarScreen },
  { id: 'gantt', title: '版本时间线（V5）', domain: '可视化看板', desc: '甘特式发布跨度', Screen: GanttScreen },
  { id: 'nodes', title: '节点状态墙（V6）', domain: '可视化看板', desc: '构建集群格子墙', Screen: NodeWallScreen },
  // 用户域 U（增量三）
  { id: 'users', title: '用户列表（U1）', domain: '用户域', desc: '成员目录与工作负荷（含 U2 详情子页）', Screen: UserDirectoryScreen },
  { id: 'my-desk', title: '我的工作台（U3）', domain: '用户域', desc: '待办/进行中/动态聚合', Screen: MyDeskScreen },
  // 发布工程域 R（增量四）
  { id: 'readiness', title: '发布就绪度（R1）', domain: '发布工程域', desc: 'Go/No-Go 六项门禁', Screen: ReadinessScreen },
  { id: 'create-wizard', title: '新建发布向导（R2）', domain: '发布工程域', desc: '三步创建形态', Screen: CreateWizardScreen },
  { id: 'artifacts', title: '制品库（R3）', domain: '发布工程域', desc: '跨单产物聚合', Screen: ArtifactScreen },
  { id: 'version-diff', title: '版本对比（R4）', domain: '发布工程域', desc: '字段级 diff', Screen: DiffScreen },
  // 环境域 H（增量四）
  { id: 'envs', title: '环境中心（H1）', domain: '环境域', desc: '三环境概览与详情', Screen: EnvScreen },
  { id: 'topology', title: '部署拓扑图（H2）', domain: '环境域', desc: '应用→渠道关系', Screen: TopologyScreen },
  // 稳定性域 S（增量四）
  { id: 'incidents', title: '事故与事件流（S1）', domain: '稳定性域', desc: '失败→事故 + MTTR', Screen: IncidentScreen },
  { id: 'gray', title: '灰度发布管理（S2）', domain: '稳定性域', desc: '分阶段放量控制', Screen: GrayScreen },
  // 集成域 G（增量四）
  { id: 'integrations', title: '集成中心（G1）', domain: '集成域', desc: '外部系统连接状态', Screen: IntegrationScreen },
  // 横向专项 X（增量四）
  { id: 'search', title: '全局搜索中心（X1）', domain: '横向专项', desc: '聚合搜索结果页', Screen: SearchScreen },
  { id: 'components', title: '通用组件样例库（F7）', domain: '横向专项', desc: '高频通用件 RN 等价形态', Screen: ComponentGalleryScreen },
];

const QUICK_ACTIONS = [
  { label: 'TICK 一次（推进构建）', run: (dispatch: (a: never) => boolean) => dispatch({ type: 'TICK' } as never) },
];

/** APP 原型导航器：索引列表 → 原型屏（全屏覆盖层，经设置页「原型预览（内部）」进入）。 */
export default function ProtoNavigator({ onClose }: { onClose: () => void }) {
  const accent = useAccent();
  const dispatch = useStore((s) => s.dispatch);
  const [current, setCurrent] = useState<ProtoEntry | null>(null);
  const [quickOpen, setQuickOpen] = useState(false);
  const [flash, showFlash] = useFlash();

  if (current) {
    const Screen = current.Screen;
    return <Screen onBack={() => setCurrent(null)} />;
  }

  return (
    <View style={{ flex: 1, backgroundColor: palette.bg }}>
      <View style={styles.topbar}>
        <Pressable accessibilityLabel="关闭原型预览" accessibilityRole="button" onPress={onClose} style={styles.closeBtn}>
          <Text style={{ color: accent, fontSize: 13, fontWeight: '700' }}>✕ 退出原型</Text>
        </Pressable>
        <View style={{ flexDirection: 'row', gap: 8, alignItems: 'center' }}>
          <Tag label="原型预览" color="#cfa14f" />
          <Button label="快捷操作" variant="ghost" small onPress={() => setQuickOpen(true)} />
        </View>
      </View>
      <View style={{ padding: 16, gap: 6, flex: 1 }}>
        <Text style={{ color: palette.text, fontSize: 21, fontWeight: '800' }}>原型索引</Text>
        <Text style={{ color: palette.text2, fontSize: 12.5, lineHeight: 19 }}>
          共 {REGISTRY.length} 个原型，与 Web 端 /prototype 同构。演示数据、不落库、交互仅本地生效。
        </Text>
        {flash ? (
          <View style={{ borderWidth: 1, borderColor: `${accent}33`, borderRadius: 10, paddingVertical: 8, paddingHorizontal: 12, marginTop: 6 }}>
            <Text style={{ color: accent, fontSize: 12 }}>{flash}</Text>
          </View>
        ) : null}
        <View style={{ gap: 14, marginTop: 8, paddingBottom: 32 }}>
          {DOMAINS.map((domain) => {
            const items = REGISTRY.filter((e) => e.domain === domain);
            return (
              <View key={domain} style={{ gap: 8 }}>
                <Tag label={`${domain} · ${items.length}`} color={accent} />
                {items.map((e) => (
                  <Pressable
                    key={e.id}
                    accessibilityRole="button"
                    accessibilityLabel={`打开原型 ${e.title}`}
                    onPress={() => setCurrent(e)}
                    style={({ pressed }) => [
                      styles.entry,
                      pressed && { opacity: 0.7, borderColor: `${accent}66` },
                    ]}
                  >
                    <View style={{ flex: 1 }}>
                      <Text style={{ color: palette.text, fontSize: 14, fontWeight: '700' }}>{e.title}</Text>
                      <Muted style={{ fontSize: 12, marginTop: 2 }}>{e.desc}</Muted>
                    </View>
                    <Text style={{ color: accent, fontSize: 16 }}>›</Text>
                  </Pressable>
                ))}
              </View>
            );
          })}
        </View>
      </View>

      <Modal visible={quickOpen} transparent animationType="fade" onRequestClose={() => setQuickOpen(false)}>
        <Pressable style={styles.overlay} onPress={() => setQuickOpen(false)}>
          <View style={styles.sheet}>
            <Text style={{ color: palette.text, fontSize: 15, fontWeight: '700', marginBottom: 10 }}>快捷操作（原型）</Text>
            {QUICK_ACTIONS.map((a) => (
              <Button
                key={a.label}
                label={a.label}
                onPress={() => {
                  const ok = (dispatch as (action: { type: string }) => boolean)({ type: 'TICK' });
                  showFlash(ok ? '已执行一次 TICK（正式 store）' : '没有可推进的构建');
                  setQuickOpen(false);
                }}
              />
            ))}
            <Button label="关闭" variant="ghost" onPress={() => setQuickOpen(false)} />
          </View>
        </Pressable>
      </Modal>
    </View>
  );
}

const styles = StyleSheet.create({
  topbar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingTop: 10,
    paddingBottom: 8,
    borderBottomWidth: 1,
    borderBottomColor: palette.border,
  },
  closeBtn: { paddingVertical: 6, paddingRight: 8 },
  entry: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    borderWidth: 1,
    borderColor: palette.border,
    borderRadius: 12,
    padding: 12,
    backgroundColor: palette.surface,
  },
  overlay: { flex: 1, backgroundColor: 'rgba(0,0,0,0.4)', justifyContent: 'flex-end' },
  sheet: { backgroundColor: palette.surface, padding: 16, gap: 10, borderTopLeftRadius: 16, borderTopRightRadius: 16 },
});
