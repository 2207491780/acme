import { useState } from 'react';
import { StyleSheet, Text, TextInput, View } from 'react-native';
import { useStore } from '../../store/useStore';
import { Button, Muted } from '../../components/ui';
import { ProtoShell, Section, useFlash } from './kit';
import { palette, TONE_COLOR } from '../../theme';

const MAX_ATTEMPTS = 5;

/** L1 登录页（APP 形态 · Acme SaaS 云平台 · 多租户）：租户代码前置 + 品牌区 + 算术验证码；
 *  加载 / 错误（租户、口令、验证码）/ 锁定 / 成功四态。交互仅本地生效；
 *  正式契约见 platform-auth /app/v1/auth/tokens（JWS claims 携带 tenantId）。 */
export default function LoginScreen({ onBack }: { onBack: () => void }) {
  const apps = useStore((s) => s.state.apps);
  const [tenant, setTenant] = useState('acme');
  const [account, setAccount] = useState('demo');
  const [password, setPassword] = useState('');
  const [answer, setAnswer] = useState('');
  const [loading, setLoading] = useState(false);
  const [attempts, setAttempts] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [flash, showFlash] = useFlash();

  const locked = attempts >= MAX_ATTEMPTS;
  const captcha = { a: 3, b: 4 };

  const fail = (why: string) => {
    const left = Math.max(MAX_ATTEMPTS - attempts - 1, 0);
    setAttempts((n) => Math.min(n + 1, MAX_ATTEMPTS));
    setError(`${why}（演示；连续失败 ${MAX_ATTEMPTS} 次锁定，剩余尝试 ${left} 次）`);
    setAnswer('');
  };

  const submit = () => {
    setError(null);
    if (Number(answer) !== captcha.a + captcha.b) {
      fail(`验证码不正确（${captcha.a} + ${captcha.b} = ?）`);
      return;
    }
    setLoading(true);
    setTimeout(() => {
      setLoading(false);
      if (tenant === 'nope') {
        fail(`租户「${tenant}」不存在`);
        return;
      }
      if (password === 'wrong') {
        fail('账号或密码不正确');
        return;
      }
      showFlash(`原型演示：租户 ${tenant || 'acme'} · ${account || 'demo'} 登录成功（claims.tenantId=${tenant || 'acme'}）`);
    }, 1000);
  };

  return (
    <ProtoShell
      title="登录页"
      domain="认证域 L1"
      desc="Acme SaaS 云平台 · 多租户登录：租户代码 / 品牌区 / 表单 / 四态走查。"
      onBack={onBack}
      flash={flash}
    >
      <View style={styles.brand}>
        <Text style={{ color: '#fff', fontSize: 24, fontWeight: '900' }}>⚡ Acme SaaS 云平台</Text>
        <Text style={{ color: 'rgba(255,255,255,0.8)', fontSize: 11, letterSpacing: 2 }}>MULTI-TENANT CLOUD · APP 形态</Text>
        <Tag label={`租户 ${tenant || '未填'} · ${apps.length} 个应用在册（演示数据）`} color={TONE_COLOR.info} />
      </View>
      {error ? (
        <View style={[styles.banner, { borderColor: TONE_COLOR.danger }]}>
          <Text style={{ color: TONE_COLOR.danger, fontSize: 12 }}>{error}</Text>
        </View>
      ) : null}
      {locked ? (
        <View style={[styles.banner, { borderColor: TONE_COLOR.warning }]}>
          <Text style={{ color: TONE_COLOR.warning, fontSize: 12 }}>演示锁定：连续失败达上限（正式实现为服务端 60s 冷却）</Text>
        </View>
      ) : null}
      <Section title="登录（演示：租户 nope / 密码 wrong 体验失败态，其余密码 ≥ 6 位成功）">
        <TextInput
          value={tenant}
          onChangeText={setTenant}
          placeholder="租户代码"
          placeholderTextColor={palette.muted}
          autoCapitalize="none"
          accessibilityLabel="租户代码"
          style={styles.input}
        />
        <TextInput
          value={account}
          onChangeText={setAccount}
          placeholder="账号（租户内唯一）"
          placeholderTextColor={palette.muted}
          autoCapitalize="none"
          accessibilityLabel="账号"
          style={styles.input}
        />
        <TextInput
          value={password}
          onChangeText={setPassword}
          placeholder="密码"
          placeholderTextColor={palette.muted}
          secureTextEntry
          accessibilityLabel="密码"
          style={styles.input}
        />
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, marginBottom: 10 }}>
          <Text style={{ color: palette.text2, fontSize: 13 }}>
            验证码 {captcha.a} + {captcha.b} = ?
          </Text>
          <TextInput
            value={answer}
            onChangeText={setAnswer}
            placeholder="答案"
            placeholderTextColor={palette.muted}
            keyboardType="number-pad"
            accessibilityLabel="验证码答案"
            style={[styles.input, { flex: 1, marginBottom: 0 }]}
          />
        </View>
        <Muted>记住租户与账号（7 天，演示） · 忘记密码 → 租户管理员重置</Muted>
      </Section>
      <Button label={loading ? '验签中…' : '登录'} onPress={submit} disabled={locked || loading} />
      <Muted style={{ textAlign: 'center' }}>JWS claims 携带 tenantId；数据 / 配置 / 配额按租户隔离</Muted>
    </ProtoShell>
  );
}

const styles = StyleSheet.create({
  brand: {
    borderRadius: 14,
    padding: 18,
    gap: 8,
    backgroundColor: palette.elevated,
    borderWidth: 1,
    borderColor: palette.border,
  },
  banner: {
    borderRadius: 10,
    borderWidth: 1,
    padding: 10,
    backgroundColor: palette.surface,
  },
  input: {
    borderWidth: 1,
    borderColor: palette.border,
    borderRadius: 12,
    backgroundColor: palette.surface,
    padding: 10,
    fontSize: 13,
    color: palette.text,
    marginBottom: 10,
  },
});
