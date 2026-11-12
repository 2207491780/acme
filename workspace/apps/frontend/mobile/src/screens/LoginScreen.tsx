import { useEffect, useState } from 'react';
import { KeyboardAvoidingView, Platform, StyleSheet, Text, TextInput, View } from 'react-native';
import { Button, Card, Muted, Title } from '../components/ui';
import { palette } from '../theme';
import { ApiError } from '../api/client';
import { authApi } from '../api/auth';
import { useAuthStore } from '../store/authStore';

const PHONE_PATTERN = /^1\d{10}$/;
const CAPTCHA_PATTERN = /^\d{6}$/;
const RESEND_INTERVAL_SECONDS = 60;

/** 登录屏（login-app）：手机号 + 短信验证码真实登录（对接 /app/v1/auth）。
 *  首登自动注册；签发双令牌（access 2h / refresh 14d）经安全存储持久化；
 *  手机号 / 验证码格式与后端契约同口径校验（^1\d{10}$ / ^\d{6}$）。 */
export function LoginScreen() {
  const [phone, setPhone] = useState('');
  const [captcha, setCaptcha] = useState('');
  const [sending, setSending] = useState(false);
  const [resendIn, setResendIn] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (resendIn <= 0) {
      return undefined;
    }
    const timer = setTimeout(() => setResendIn((n) => n - 1), 1000);
    return () => clearTimeout(timer);
  }, [resendIn]);

  const sendCaptcha = () => {
    setError(null);
    if (!PHONE_PATTERN.test(phone)) {
      setError('手机号格式不正确（11 位大陆手机号）');
      return;
    }
    setSending(true);
    authApi.sendCaptcha(phone)
      .then(() => {
        setResendIn(RESEND_INTERVAL_SECONDS);
        setError(null);
      })
      .catch((ex: unknown) => {
        setError(ex instanceof ApiError ? ex.message : '验证码发送失败，请稍后重试');
      })
      .finally(() => setSending(false));
  };

  const login = () => {
    setError(null);
    if (!PHONE_PATTERN.test(phone)) {
      setError('手机号格式不正确（11 位大陆手机号）');
      return;
    }
    if (!CAPTCHA_PATTERN.test(captcha)) {
      setError('验证码为 6 位数字');
      return;
    }
    setLoading(true);
    authApi.login(phone, captcha)
      .then((issued) => {
        useAuthStore.getState().setSession({
          accessToken: issued.accessToken,
          refreshToken: issued.refreshToken,
          accessExpiresAtMs: Date.parse(issued.expiresAt),
          refreshExpiresAtMs: Date.parse(issued.refreshExpiresAt),
          userId: issued.userId,
          phone: issued.phone,
        });
        // App 门禁据 session 自动切换主界面（无需手动导航）
      })
      .catch((ex: unknown) => {
        setError(ex instanceof ApiError ? ex.message : '登录失败，请稍后重试');
        setCaptcha('');
      })
      .finally(() => setLoading(false));
  };

  return (
    <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={styles.flex}>
      <Card style={styles.card}>
        <Title>登录</Title>
        <Muted>Acme SaaS 云平台 · 手机号验证码登录，首次登录自动注册</Muted>
        {error ? (
          <View style={[styles.banner, { borderColor: palette.muted }]}>
            <Text style={{ color: palette.text2, fontSize: 12 }}>{error}</Text>
          </View>
        ) : null}
        <TextInput
          value={phone}
          onChangeText={setPhone}
          placeholder="手机号"
          placeholderTextColor={palette.muted}
          keyboardType="number-pad"
          maxLength={11}
          autoComplete="tel"
          accessibilityLabel="手机号"
          style={styles.input}
        />
        <View style={styles.captchaRow}>
          <TextInput
            value={captcha}
            onChangeText={setCaptcha}
            placeholder="6 位短信验证码"
            placeholderTextColor={palette.muted}
            keyboardType="number-pad"
            maxLength={6}
            accessibilityLabel="短信验证码"
            style={[styles.input, { flex: 1, marginBottom: 0 }]}
          />
          <Button
            label={resendIn > 0 ? `${resendIn}s 后重发` : '获取验证码'}
            onPress={sendCaptcha}
            small
            disabled={sending || resendIn > 0}
          />
        </View>
        <Button label={loading ? '登录中…' : '登录'} onPress={login} disabled={loading} />
        <Muted style={{ textAlign: 'center' }}>
          会话为 2 小时短令牌 + 14 天刷新令牌，自动滑动续约；退出登录即刻失效。
        </Muted>
      </Card>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  card: { gap: 12 },
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
  captchaRow: { flexDirection: 'row', alignItems: 'center', gap: 8, marginBottom: 10 },
});
