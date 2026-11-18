import { useEffect, useMemo, useState } from 'react';
import { KeyboardAvoidingView, Platform, StyleSheet, Text, TextInput, View } from 'react-native';
import type { NeutralPalette } from '@acme/core';
import { Button, Card, Muted, Title } from '../components/ui';
import { ApiError } from '../api/client';
import { authApi } from '../api/auth';
import { useAuthStore } from '../store/authStore';
import { useAppTheme } from '../theme';
import { useTranslation } from '../i18n';

const PHONE_PATTERN = /^1\d{10}$/;
const CAPTCHA_PATTERN = /^\d{6}$/;
const RESEND_INTERVAL_SECONDS = 60;

/** 登录屏（login-app，Stack 首屏）：手机号 + 短信验证码真实登录（对接 /app/v1/auth）。
 *  首登自动注册；签发双令牌（access 2h / refresh 14d）经安全存储持久化；
 *  手机号 / 验证码格式与后端契约同口径校验（^1\d{10}$ / ^\d{6}$）。
 *  会话建立后由 App 壳 AuthNavigator replace 主 Tabs（契约⑨）。 */
export function LoginScreen() {
  const [phone, setPhone] = useState('');
  const [captcha, setCaptcha] = useState('');
  const [sending, setSending] = useState(false);
  const [resendIn, setResendIn] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const { t } = useTranslation();
  const { palette } = useAppTheme();
  const styles = useMemo(() => makeStyles(palette), [palette]);

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
      setError(t('login.mobile.phoneInvalid'));
      return;
    }
    setSending(true);
    authApi.sendCaptcha(phone)
      .then(() => {
        setResendIn(RESEND_INTERVAL_SECONDS);
        setError(null);
      })
      .catch((ex: unknown) => {
        setError(ex instanceof ApiError ? ex.message : t('login.mobile.captchaSendFailed'));
      })
      .finally(() => setSending(false));
  };

  const login = () => {
    setError(null);
    if (!PHONE_PATTERN.test(phone)) {
      setError(t('login.mobile.phoneInvalid'));
      return;
    }
    if (!CAPTCHA_PATTERN.test(captcha)) {
      setError(t('login.mobile.captchaInvalid'));
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
        // App 门禁据 session 自动切换主界面（AuthNavigator replace 主 Tabs）
      })
      .catch((ex: unknown) => {
        setError(ex instanceof ApiError ? ex.message : t('login.errors.loginFailed'));
        setCaptcha('');
      })
      .finally(() => setLoading(false));
  };

  return (
    <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={styles.flex}>
      <Card style={styles.card}>
        <Title>{t('login.mobile.title')}</Title>
        <Muted>{t('login.mobile.subtitle')}</Muted>
        {error ? (
          <View style={[styles.banner, { borderColor: palette.muted }]}>
            <Text style={[styles.bannerText, { color: palette.text2 }]}>{error}</Text>
          </View>
        ) : null}
        <TextInput
          value={phone}
          onChangeText={setPhone}
          placeholder={t('login.mobile.phone')}
          placeholderTextColor={palette.muted}
          keyboardType="number-pad"
          maxLength={11}
          autoComplete="tel"
          accessibilityLabel={t('login.mobile.phone')}
          style={styles.input}
        />
        <View style={styles.captchaRow}>
          <TextInput
            value={captcha}
            onChangeText={setCaptcha}
            placeholder={t('login.mobile.captchaPlaceholder')}
            placeholderTextColor={palette.muted}
            keyboardType="number-pad"
            maxLength={6}
            accessibilityLabel={t('login.mobile.captcha')}
            style={[styles.input, { flex: 1, marginBottom: 0 }]}
          />
          <Button
            label={resendIn > 0 ? t('login.mobile.resendIn', { seconds: resendIn }) : t('login.mobile.getCaptcha')}
            onPress={sendCaptcha}
            small
            disabled={sending || resendIn > 0}
          />
        </View>
        <Button label={loading ? t('login.mobile.submitting') : t('login.mobile.submit')} onPress={login} disabled={loading} />
        <Muted style={{ textAlign: 'center' }}>{t('login.mobile.sessionNote')}</Muted>
      </Card>
    </KeyboardAvoidingView>
  );
}

const makeStyles = (p: NeutralPalette) =>
  StyleSheet.create({
    flex: { flex: 1 },
    card: { gap: 12 },
    banner: {
      borderRadius: 10,
      borderWidth: 1,
      padding: 10,
      backgroundColor: p.surface,
    },
    bannerText: { fontSize: 12 },
    input: {
      borderWidth: 1,
      borderColor: p.border,
      borderRadius: 12,
      backgroundColor: p.surface,
      padding: 10,
      fontSize: 13,
      color: p.text,
      marginBottom: 10,
    },
    captchaRow: { flexDirection: 'row', alignItems: 'center', gap: 8, marginBottom: 10 },
  });
