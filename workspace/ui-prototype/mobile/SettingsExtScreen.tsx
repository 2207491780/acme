import { Pressable, Switch, View } from 'react-native';
import { ACCENTS, ACCENT_ORDER, type Accent } from '@acme/core';
import { useStore } from '../../store/useStore';
import { Muted, Tag } from '../../components/ui';
import { useAccent } from '../../hooks';
import { MonoText, ProtoShell, Section } from './kit';
import { palette, TONE_COLOR } from '../../theme';

const SHORTCUT_NOTE = ['Web 端支持 Ctrl/⌘+K 命令面板（F2）', 'G+D/G+R 跳转概览/发布单', '移动端以快捷操作区替代（见原型首页）'];
const I18N_SAMPLE = [
  { key: 'release.status.published', zh: '已发布', en: 'Published' },
  { key: 'release.action.retry', zh: '重试构建', en: 'Retry build' },
];

/** E3 设置扩展（APP 形态）：强调色预览、快捷键说明、多语言预览（仅本地生效）。 */
export default function SettingsExtScreen({ onBack }: { onBack: () => void }) {
  const settings = useStore((s) => s.state.settings);
  const dispatch = useStore((s) => s.dispatch);
  const accent = useAccent();

  return (
    <ProtoShell
      title="设置扩展"
      domain="应用与配置域 E3"
      desc="设置页演进方向：主题预览、快捷键说明、多语言预览。"
      onBack={onBack}
    >
      <Section title="强调色（写入正式设置，可逆）">
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 10 }}>
          {ACCENT_ORDER.map((name: Accent) => {
            const selected = settings.accent === name;
            return (
              <Pressable
                key={name}
                accessibilityLabel={`强调色 ${name}`}
                accessibilityRole="button"
                accessibilityState={{ selected }}
                onPress={() => dispatch({ type: 'UPDATE_SETTINGS', patch: { accent: name } })}
                style={[styles.swatch, { backgroundColor: ACCENTS[name], borderColor: selected ? palette.text : 'transparent' }]}
              />
            );
          })}
        </View>
        <Muted style={{ fontSize: 12, marginTop: 8 }}>当前强调色：{settings.accent}（{accent}）</Muted>
      </Section>

      <Section title="跟随系统主题（预留）">
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
          <Switch value={false} disabled accessibilityLabel="跟随系统主题（预留占位）" />
          <Muted style={{ fontSize: 12 }}>移动端当前统一深色主题（UI-SPEC §2.4）；成熟化时接入 Appearance。</Muted>
        </View>
      </Section>

      <Section title="快捷键 / 快捷操作说明">
        {SHORTCUT_NOTE.map((line) => (
          <MonoText key={line} color={palette.text2}>· {line}</MonoText>
        ))}
      </Section>

      <Section title="多语言预览（zh / en）">
        {I18N_SAMPLE.map((row) => (
          <View key={row.key} style={{ paddingVertical: 5, gap: 3 }}>
            <MonoText color={palette.muted}>{row.key}</MonoText>
            <View style={{ flexDirection: 'row', gap: 6 }}>
              <Tag label={`中文：${row.zh}`} color={palette.muted} />
              <Tag label={`EN: ${row.en}`} color={TONE_COLOR.info} />
            </View>
          </View>
        ))}
        <Muted style={{ fontSize: 11 }}>成熟化时接入 i18n 资源包。</Muted>
      </Section>

      <Muted style={{ fontSize: 11 }}>需要回到原型索引时点左上角「‹ 原型索引」。</Muted>
    </ProtoShell>
  );
}

const styles = {
  swatch: { width: 34, height: 34, borderRadius: 99, borderWidth: 3, alignItems: 'center' as const, justifyContent: 'center' as const },
};
