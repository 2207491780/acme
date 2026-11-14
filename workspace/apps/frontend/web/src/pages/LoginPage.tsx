import { useCallback, useEffect, useState } from 'react';
import { Alert, App, Button, Card, Checkbox, Divider, Form, Input, Select, Space, Typography } from 'antd';
import { ApartmentOutlined, GlobalOutlined, LockOutlined, ReloadOutlined, SafetyCertificateOutlined, ThunderboltOutlined, UserOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ApiError, authApi, useAuthStore, type CaptchaData, type TenantSummary } from '@acme/core';
import { loginFormDefaults } from './loginDefaults';

const { Text } = Typography;

interface LoginFormValues {
  tenantCode: string;
  username: string;
  password: string;
  captchaCode: string;
  remember?: boolean;
}

/** 登录页（login-ui）：Acme SaaS 云平台多租户登录。
 *  按原型（认证域 L1）与后端契约实现：租户可搜索下拉（/auth/tenants）、图形验证码
 *  （/auth/captcha，data-URI 直接渲染、点击刷新）、表单校验、加载/错误/成功态；
 *  成功后会话写入 authStore 并跳转概览（记住我 → localStorage 持久化）。
 *  文案经 i18n 资源包（REQ-20261114-120103，login 域）。 */
export function LoginPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const navigate = useNavigate();

  const [tenants, setTenants] = useState<TenantSummary[]>([]);
  const [tenantsLoading, setTenantsLoading] = useState(true);
  const [tenantsError, setTenantsError] = useState<string | null>(null);
  const [captcha, setCaptcha] = useState<CaptchaData | null>(null);
  const [captchaLoading, setCaptchaLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [form] = Form.useForm<LoginFormValues>();

  // 开发阶段便利性：dev server 下预填超管默认凭据（生产构建不含，见 loginDefaults.ts）
  const devDefaults = loginFormDefaults(import.meta.env.DEV);

  const loadTenants = useCallback(() => {
    setTenantsLoading(true);
    setTenantsError(null);
    authApi.tenants()
      .then((list) => {
        setTenants(list);
        if (list.length > 0) {
          // 表单已填则保留用户输入；否则默认取首个租户
          const current = form.getFieldValue('tenantCode') as string | undefined;
          if (!current) {
            form.setFieldValue('tenantCode', list[0].tenantCode);
          }
        }
      })
      .catch((ex: unknown) => {
        setTenantsError(ex instanceof ApiError ? ex.message : t('login.errors.tenantsLoadFailed'));
      })
      .finally(() => setTenantsLoading(false));
  }, [form, t]);

  const loadCaptcha = useCallback(() => {
    setCaptchaLoading(true);
    authApi.captcha()
      .then(setCaptcha)
      .catch((ex: unknown) => {
        setCaptcha(null);
        setError(ex instanceof ApiError ? ex.message : t('login.errors.captchaLoadFailed'));
      })
      .finally(() => setCaptchaLoading(false));
  }, [t]);

  useEffect(() => {
    loadTenants();
    loadCaptcha();
  }, [loadTenants, loadCaptcha]);

  const refreshCaptcha = useCallback(() => {
    setError(null);
    loadCaptcha();
  }, [loadCaptcha]);

  const onFinish = (values: LoginFormValues) => {
    if (!captcha) {
      setError(t('login.errors.captchaNotReady'));
      return;
    }
    setSubmitting(true);
    setError(null);
    authApi.login({
      tenantCode: values.tenantCode,
      username: values.username,
      password: values.password,
      captchaId: captcha.captchaId,
      captchaCode: values.captchaCode,
    })
      .then((issued) => {
        const remember = form.getFieldValue('remember') as boolean | undefined;
        useAuthStore.getState().setSession({
          accessToken: issued.accessToken,
          expiresAtMs: Date.parse(issued.expiresAt),
          userId: issued.userId,
          username: issued.username,
          nickname: issued.nickname,
          tenantCode: values.tenantCode,
        }, remember ?? false);
        message.success(t('login.welcome', { name: issued.nickname || issued.username }));
        // 登录成功后刷新验证码（一次性消费已完成，避免回退页复用）
        loadCaptcha();
        navigate('/', { replace: true });
      })
      .catch((ex: unknown) => {
        setError(ex instanceof ApiError ? ex.message : t('login.errors.loginFailed'));
        loadCaptcha();
      })
      .finally(() => setSubmitting(false));
  };

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 24,
        background: 'var(--bg-0)',
      }}
    >
      <div style={{ width: '100%', maxWidth: 960 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 18 }}>
          <ThunderboltOutlined style={{ fontSize: 26, color: 'var(--accent)' }} />
          <div>
            <div style={{ fontSize: 18, fontWeight: 800, color: 'var(--text)' }}>{t('login.brand.title')}</div>
            <div style={{ fontSize: 11, letterSpacing: 2, color: 'var(--muted)' }}>
              {t('login.brand.subtitle')}
            </div>
          </div>
        </div>
        <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap', alignItems: 'stretch' }}>
          <div
            style={{
              flex: '1 1 300px',
              minWidth: 280,
              minHeight: 420,
              borderRadius: 14,
              padding: 28,
              color: '#fff',
              display: 'flex',
              flexDirection: 'column',
              justifyContent: 'space-between',
              background: 'linear-gradient(160deg, var(--accent) 0%, color-mix(in srgb, var(--accent) 38%, var(--bg-0)) 72%)',
            }}
          >
            <div style={{ display: 'grid', gap: 14 }}>
              <Space align="start"><ApartmentOutlined style={{ marginTop: 3 }} /><Text style={{ color: '#fff' }}>{t('login.features.tenantIsolation')}</Text></Space>
              <Space align="start"><SafetyCertificateOutlined style={{ marginTop: 3 }} /><Text style={{ color: '#fff' }}>{t('login.features.gatewayAuth')}</Text></Space>
              <Space align="start"><GlobalOutlined style={{ marginTop: 3 }} /><Text style={{ color: '#fff' }}>{t('login.features.multiClient')}</Text></Space>
            </div>
            <div>
              <Text style={{ color: '#fff', opacity: 0.85, fontSize: 13 }}>
                {t('login.features.accessNote')}
              </Text>
              <div style={{ marginTop: 8 }}>
                <Text style={{ color: '#fff', opacity: 0.6, fontSize: 12 }}>
                  {t('login.features.sessionNote')}
                </Text>
              </div>
            </div>
          </div>
          <Card style={{ flex: '1 1 340px', minWidth: 320, maxWidth: 470 }} styles={{ body: { padding: '22px 24px' } }}>
            {error && <Alert type="error" showIcon style={{ marginBottom: 16 }} message={error} closable onClose={() => setError(null)} />}
            {tenantsError && (
              <Alert
                type="warning"
                showIcon
                style={{ marginBottom: 16 }}
                message={tenantsError}
                action={<Button size="small" onClick={loadTenants}>{t('login.actions.retry')}</Button>}
              />
            )}
            <Form<LoginFormValues>
              form={form}
              layout="vertical"
              onFinish={onFinish}
              initialValues={{ remember: true, ...(devDefaults ?? {}) }}
              requiredMark
            >
              <Form.Item
                name="tenantCode"
                label={t('login.form.tenant')}
                rules={[{ required: true, message: t('login.form.tenantRequired') }]}
              >
                <Select
                  showSearch
                  placeholder={tenantsLoading ? t('login.form.tenantLoading') : t('login.form.tenantSelect')}
                  optionFilterProp="label"
                  options={tenants.map((item) => ({ value: item.tenantCode, label: `${item.tenantName}（${item.tenantCode}）` }))}
                  loading={tenantsLoading}
                  notFoundContent={tenantsLoading ? t('common.status.loading') : t('login.form.tenantEmpty')}
                  aria-label={t('login.form.tenant')}
                />
              </Form.Item>
              <Form.Item
                name="username"
                label={t('login.form.username')}
                rules={[
                  { required: true, message: t('login.form.usernameRequired') },
                  { min: 3, max: 64, message: t('login.form.usernameLength') },
                ]}
              >
                <Input prefix={<UserOutlined style={{ color: 'var(--muted)' }} />} placeholder={t('login.form.usernamePlaceholder')} autoComplete="username" />
              </Form.Item>
              <Form.Item
                name="password"
                label={t('login.form.password')}
                rules={[
                  { required: true, message: t('login.form.passwordRequired') },
                  { min: 1, max: 64, message: t('login.form.passwordLength') },
                ]}
              >
                <Input.Password prefix={<LockOutlined style={{ color: 'var(--muted)' }} />} placeholder={t('login.form.password')} autoComplete="current-password" />
              </Form.Item>
              <Form.Item
                name="captchaCode"
                label={t('login.form.captcha')}
                rules={[
                  { required: true, message: t('login.form.captchaRequired') },
                  { min: 4, max: 4, message: t('login.form.captchaLength') },
                ]}
                style={{ marginBottom: 12 }}
              >
                <Space.Compact style={{ width: '100%' }}>
                  <Input placeholder={t('login.form.captchaInput')} maxLength={4} aria-label={t('login.form.captcha')} />
                  <Button
                    onClick={refreshCaptcha}
                    style={{ width: 118, paddingInline: 8, userSelect: 'none' }}
                    title={t('login.form.captchaRefreshTitle')}
                    disabled={captchaLoading}
                  >
                    {captcha
                      ? (
                        <img
                          src={captcha.imageBase64Data}
                          alt={t('login.form.captchaImageAlt')}
                          style={{ height: 26, display: 'block' }}
                        />
                      )
                      : <ReloadOutlined />}
                    <ReloadOutlined style={{ marginLeft: 4, color: 'var(--muted)' }} />
                  </Button>
                </Space.Compact>
              </Form.Item>
              <Form.Item name="remember" valuePropName="checked" style={{ marginBottom: 18 }}>
                <Checkbox>{t('login.form.remember')}</Checkbox>
              </Form.Item>
              <Button block type="primary" htmlType="submit" loading={submitting} icon={<ThunderboltOutlined />}>
                {t('login.form.submit')}
              </Button>
              <Divider plain style={{ marginBlock: 14 }}>
                <Text type="secondary" style={{ fontSize: 12 }}>{t('login.session.title')}</Text>
              </Divider>
              <Text type="secondary" style={{ fontSize: 12, display: 'block', textAlign: 'center' }}>
                {t('login.session.detail')}
              </Text>
            </Form>
          </Card>
        </div>
      </div>
    </div>
  );
}
