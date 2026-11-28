import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Image,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import type { NeutralPalette } from '@acme/core';
import { Button, Card, Muted, Title } from '../components/ui';
import { ApiError } from '../api/client';
import { authApi, type AdminCaptchaData } from '../api/auth';
import { useAuthStore } from '../store/authStore';
import { useBSessionStore } from '../store/bSessionStore';
import { useAppTheme } from '../theme';
import { useAccent, useOnAccent } from '../hooks';
import { useTranslation } from '../i18n';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { QR_CONFIRM_ROUTE, type RootStackParamList } from '../nav';

const PHONE_PATTERN = /^1\d{10}$/;
const CAPTCHA_PATTERN = /^\d{6}$/;
const RESEND_INTERVAL_SECONDS = 60;
/** 员工账密登录默认租户（委派口径：tenantCode 缺省 default）。 */
const DEFAULT_TENANT_CODE = 'default';

type LoginMode = 'sms' | 'employee';

const MODES: readonly LoginMode[] = ['sms', 'employee'];

/** 登录屏（login-app，Stack 首屏）：双登录方式切换（REQ-20261119-040838）。
 *  - 手机号验证码（C 端既有）：手机号 + 短信验证码真实登录（对接 /app/v1/auth），首登自动注册，
 *    签发双令牌（access 2h / refresh 14d）经安全存储持久化，会话建立后由 AuthNavigator replace 主 Tabs。
 *  - 员工账密（B 端新增）：租户 + 账号 + 密码 + 图形验证码（按租户 captcha-policy 显隐，
 *    策略查询失败 fail-closed 按开启），POST /admin/v1/auth/tokens 成功后写入 BSessionStore
 *    （acme.auth.bsession.v1）——B 会话不进主 Tabs，仅用于扫码确认（QrConfirm 入口）。 */
export function LoginScreen() {
  const [mode, setMode] = useState<LoginMode>('sms');
  // ————— C 端手机号验证码域（既有） —————
  const [phone, setPhone] = useState('');
  const [captcha, setCaptcha] = useState('');
  const [sending, setSending] = useState(false);
  const [resendIn, setResendIn] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // ————— B 端员工账密域（REQ-20261119-040838） —————
  const [tenantCode, setTenantCode] = useState(DEFAULT_TENANT_CODE);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [imageCaptcha, setImageCaptcha] = useState('');
  const [adminCaptcha, setAdminCaptcha] = useState<AdminCaptchaData | null>(null);
  /** 员工登录错误（与 C 端 error 分离，两 Tab 互不污染）。 */
  const [employeeError, setEmployeeError] = useState<string | null>(null);
  const [employeeLoading, setEmployeeLoading] = useState(false);
  /** 租户验证码策略：null=在途/未定（fail-closed：提交要求验证码，输入框与图形码请求暂缓）。 */
  const [captchaPolicyOn, setCaptchaPolicyOn] = useState<boolean | null>(null);
  const bSession = useBSessionStore((s) => s.session);
  const { t } = useTranslation();
  const { palette } = useAppTheme();
  const accent = useAccent();
  const onAccent = useOnAccent();
  const navigation = useNavigation<NativeStackNavigationProp<RootStackParamList>>();
  const styles = useMemo(() => makeStyles(palette), [palette]);

  useEffect(() => {
    if (resendIn <= 0) {
      return undefined;
    }
    const timer = setTimeout(() => setResendIn((n) => n - 1), 1000);
    return () => clearTimeout(timer);
  }, [resendIn]);

  // 员工 Tab + 租户确定 → 查询验证码策略（失败 fail-closed 视为开启，与 Web 登录页口径一致）
  useEffect(() => {
    if (mode !== 'employee' || !tenantCode.trim()) {
      return;
    }
    let active = true;
    setCaptchaPolicyOn(null);
    authApi.adminCaptchaPolicy(tenantCode.trim())
      .then((policy) => {
        if (active) {
          setCaptchaPolicyOn(policy.captchaEnabled === true);
        }
      })
      .catch(() => {
        if (active) {
          setCaptchaPolicyOn(true);
        }
      });
    return () => {
      active = false;
    };
  }, [mode, tenantCode]);

  // 策略开 → 请求图形验证码；策略关/未定 → 不请求（契约确认记录②：仅 captcha-policy 开启时渲染）。
  // active 守卫（P3-06，对齐上方 captcha-policy effect 先例）：租户/模式切换后旧响应不得覆盖新验证码态
  useEffect(() => {
    if (mode === 'employee' && captchaPolicyOn) {
      let active = true;
      authApi.adminCaptcha()
        .then((data) => {
          if (active) {
            setAdminCaptcha(data);
          }
        })
        .catch(() => {
          if (active) {
            setAdminCaptcha(null);
          }
        });
      return () => {
        active = false;
      };
    }
    setAdminCaptcha(null);
  }, [mode, captchaPolicyOn]);

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
          // REQ-20261127-234158 wire 对齐：C 端 AppTokenIssued 键为 accessExpiresAt（不存在 expiresAt）
          accessExpiresAtMs: Date.parse(issued.accessExpiresAt),
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

  /** 员工账密登录（REQ-20261119-040838 AC-7）：凭据 + 图形验证码（按策略）→ B 会话独立落盘。 */
  const employeeLogin = useCallback(() => {
    setEmployeeError(null);
    const tenant = tenantCode.trim();
    if (!tenant) {
      setEmployeeError(t('login.mobile.tenantRequired'));
      return;
    }
    if (!username.trim() || !password) {
      setEmployeeError(t('login.mobile.employeeCredentialRequired'));
      return;
    }
    // fail-closed：策略未定一律要求验证码（含策略查询在途），对齐后端 LoginUseCase 口径
    const captchaRequired = captchaPolicyOn !== false;
    if (captchaRequired && !adminCaptcha) {
      setEmployeeError(t('login.mobile.imageCaptchaNotReady'));
      return;
    }
    setEmployeeLoading(true);
    authApi.adminLogin({
      tenantCode: tenant,
      username: username.trim(),
      password,
      ...(captchaRequired && adminCaptcha ? { captchaId: adminCaptcha.captchaId, captchaCode: imageCaptcha } : {}),
    })
      .then((issued) => {
        useBSessionStore.getState().setSession({
          accessToken: issued.accessToken,
          accessExpiresAtMs: Date.parse(issued.expiresAt),
          userId: issued.userId,
          username: issued.username,
          nickname: issued.nickname,
          tenantCode: tenant,
        });
        setEmployeeError(null);
        setImageCaptcha('');
        // B 登录成功不进主 Tabs（主 Tabs 属 C 端会话）；QrConfirm 入口随 bSession 出现
      })
      .catch((ex: unknown) => {
        setEmployeeError(ex instanceof ApiError ? ex.message : t('login.mobile.employeeLoginFailed'));
        // 验证码一次性消费 → 失败即刷新（策略关不请求验证码接口）
        if (captchaPolicyOn) {
          authApi.adminCaptcha()
            .then((data) => setAdminCaptcha(data))
            .catch(() => setAdminCaptcha(null));
        }
      })
      .finally(() => setEmployeeLoading(false));
  }, [tenantCode, username, password, imageCaptcha, adminCaptcha, captchaPolicyOn]);

  const refreshAdminCaptcha = useCallback(() => {
    setEmployeeError(null);
    authApi.adminCaptcha()
      .then((data) => setAdminCaptcha(data))
      .catch(() => setAdminCaptcha(null));
  }, []);

  return (
    <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={styles.flex}>
      <Card style={styles.card}>
        <Title>{t('login.mobile.title')}</Title>
        <Muted>{t('login.mobile.subtitle')}</Muted>
        {/* 登录方式切换（REQ-20261119-040838）：手机号验证码（C 端）/ 员工账密（B 端） */}
        <View style={styles.modeRow} accessibilityRole="tablist">
          {MODES.map((m) => {
            const active = mode === m;
            const label = m === 'sms' ? t('login.mobile.modeSms') : t('login.mobile.modeEmployee');
            return (
              <Pressable
                key={m}
                accessibilityRole="tab"
                accessibilityState={{ selected: active }}
                accessibilityLabel={label}
                onPress={() => setMode(m)}
                style={[styles.modeItem, active && { backgroundColor: accent, borderColor: accent }]}
                testID={`login-mode-${m}`}
              >
                <Text style={[styles.modeText, { color: active ? onAccent : palette.text2 }]}>{label}</Text>
              </Pressable>
            );
          })}
        </View>
        {mode === 'sms' ? (
          <>
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
          </>
        ) : (
          <>
            <Muted>{t('login.mobile.employeeTitle')}</Muted>
            {employeeError ? (
              <View style={[styles.banner, { borderColor: palette.muted }]}>
                <Text style={[styles.bannerText, { color: palette.text2 }]}>{employeeError}</Text>
              </View>
            ) : null}
            <TextInput
              value={tenantCode}
              onChangeText={setTenantCode}
              placeholder={t('login.mobile.tenant')}
              placeholderTextColor={palette.muted}
              autoCapitalize="none"
              autoCorrect={false}
              accessibilityLabel={t('login.mobile.tenant')}
              testID="tenant-input"
              style={styles.input}
            />
            <TextInput
              value={username}
              onChangeText={setUsername}
              placeholder={t('login.mobile.usernamePlaceholder')}
              placeholderTextColor={palette.muted}
              autoCapitalize="none"
              autoCorrect={false}
              accessibilityLabel={t('login.mobile.username')}
              testID="username-input"
              style={styles.input}
            />
            <TextInput
              value={password}
              onChangeText={setPassword}
              placeholder={t('login.mobile.password')}
              placeholderTextColor={palette.muted}
              secureTextEntry
              accessibilityLabel={t('login.mobile.password')}
              testID="password-input"
              style={styles.input}
            />
            {/* 图形验证码：仅当租户策略开启时渲染（契约确认记录②）；data URI 由 Image 直接渲染 */}
            {captchaPolicyOn && (
              <>
                <View style={styles.captchaRow}>
                  <TextInput
                    value={imageCaptcha}
                    onChangeText={setImageCaptcha}
                    placeholder={t('login.mobile.imageCaptchaPlaceholder')}
                    placeholderTextColor={palette.muted}
                    autoCapitalize="none"
                    accessibilityLabel={t('login.mobile.imageCaptcha')}
                    testID="image-captcha-input"
                    style={[styles.input, { flex: 1, marginBottom: 0 }]}
                  />
                  <Pressable
                    onPress={refreshAdminCaptcha}
                    accessibilityRole="imagebutton"
                    accessibilityLabel={t('login.mobile.imageCaptchaRefresh')}
                    disabled={!adminCaptcha}
                    style={styles.imageCaptchaHit}
                    testID="admin-captcha"
                  >
                    {adminCaptcha ? (
                      <Image
                        source={{ uri: adminCaptcha.imageBase64Data }}
                        accessibilityLabel={t('login.mobile.imageCaptcha')}
                        testID="admin-captcha-image"
                        style={styles.imageCaptcha}
                      />
                    ) : null}
                  </Pressable>
                </View>
                <Muted style={{ fontSize: 11 }}>{t('login.mobile.imageCaptchaRefresh')}</Muted>
              </>
            )}
            <Button
              label={employeeLoading ? t('login.mobile.employeeSubmitting') : t('login.mobile.employeeSubmit')}
              onPress={employeeLogin}
              disabled={employeeLoading}
              testID="employee-login"
            />
            {/* 扫码确认入口：有 B 会话方可确认（无则引导先员工登录） */}
            {bSession ? (
              <Button
                label={t('login.mobile.qrConfirmEntry')}
                variant="ghost"
                onPress={() => navigation.navigate(QR_CONFIRM_ROUTE)}
                testID="qr-confirm-entry"
              />
            ) : (
              <Muted style={{ textAlign: 'center' }}>{t('login.mobile.qrNeedEmployeeSession')}</Muted>
            )}
          </>
        )}
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
    modeRow: {
      flexDirection: 'row',
      borderWidth: 1,
      borderColor: p.border,
      borderRadius: 12,
      backgroundColor: p.surface,
      padding: 3,
      gap: 3,
    },
    modeItem: {
      flex: 1,
      minHeight: 44,
      borderRadius: 10,
      borderWidth: 1,
      borderColor: 'transparent',
      alignItems: 'center',
      justifyContent: 'center',
    },
    modeText: { fontSize: 13, fontWeight: '700' },
    imageCaptchaHit: {
      minWidth: 44,
      minHeight: 44,
      alignItems: 'center',
      justifyContent: 'center',
    },
    imageCaptcha: { width: 110, height: 40, borderRadius: 6 },
  });
