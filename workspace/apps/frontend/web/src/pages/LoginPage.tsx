import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, App, Button, Card, Checkbox, Divider, Form, Input, Select, Space, Tabs, Typography } from 'antd';
import { ApartmentOutlined, GlobalOutlined, LockOutlined, ReloadOutlined, SafetyCertificateOutlined, ThunderboltOutlined, UserOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ApiError, authApi, useAuthStore, type CaptchaData, type TenantPublicConfigView, type TenantSummary, type TokenIssued } from '@acme/core';
import { loginFormDefaults } from './loginDefaults';
import { QrLoginPanel } from './QrLoginPanel';

const { Text } = Typography;

interface LoginFormValues {
  tenantCode: string;
  username: string;
  password: string;
  captchaCode: string;
  remember?: boolean;
}

/** 登录页（login-ui）：Acme SaaS 云平台多租户登录。
 *  按原型（认证域 L1）与后端契约实现：租户可搜索下拉（/auth/tenants）、验证码策略
 *  （/auth/captcha-policy，REQ-20261117-011514：所选租户确定后查询；策略关不渲染验证码输入框、
 *  不请求图形验证码接口、提交体不含验证码字段；策略请求失败按「开启」fail-closed）、图形验证码
 *  （/auth/captcha，data-URI 直接渲染、点击刷新）、表单校验、加载/错误/成功态；
 *  成功后会话写入 authStore 并跳转概览（记住我 → localStorage 持久化）。
 *  REQ-20261119-040838 增「扫码登录」Tab（账密/扫码双 Tab）：二维码创建+1.5s 轮询+确认者
 *  B 端会话写入，见 QrLoginPanel/useQrLogin。
 *  文案经 i18n 资源包（REQ-20261114-120103，login 域）。 */
export function LoginPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const navigate = useNavigate();

  const [tenants, setTenants] = useState<TenantSummary[]>([]);
  const [tenantsLoading, setTenantsLoading] = useState(true);
  const [tenantsError, setTenantsError] = useState<string | null>(null);
  /** 所选租户验证码策略：null=查询在途/未定（fail-closed：提交仍要求验证码，但输入框与图形码请求暂缓）。 */
  const [captchaPolicyOn, setCaptchaPolicyOn] = useState<boolean | null>(null);
  /** 所选租户页脚版权（REQ-20261117-025200）：独立于验证码策略的公开配置查询；
   *  null=查询在途/失败/未选中租户（失败静默不显示，页脚非关键面）。 */
  const [footerConfig, setFooterConfig] = useState<TenantPublicConfigView | null>(null);
  const [captcha, setCaptcha] = useState<CaptchaData | null>(null);
  const [captchaLoading, setCaptchaLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [form] = Form.useForm<LoginFormValues>();
  // 所选租户（默认租户确定 + 用户切换）驱动策略查询
  const tenantCode = Form.useWatch('tenantCode', form);
  // 竞态护栏：仅最新一次策略查询可落状态（快速切换租户时旧响应作废）
  const policyRequestRef = useRef('');
  // 页脚版权竞态护栏：独立于策略请求（独立 state/独立请求，可并行发起，REQ-20261117-025200）
  const footerRequestRef = useRef('');

  // 开发阶段便利性：dev server 下预填超管默认凭据（生产构建不含，见 loginDefaults.ts）
  const devDefaults = loginFormDefaults(import.meta.env.DEV);

  const loadTenants = useCallback(() => {
    setTenantsLoading(true);
    setTenantsError(null);
    authApi.tenants()
      .then((list) => {
        // 信封 data 可能非数组（如异常兜底返回 null）→ 规整为空数组，避免渲染期 tenants.map 崩溃
        //（与 OnlineUsersPage 列表规整口径一致）
        const safeList = Array.isArray(list) ? list : [];
        setTenants(safeList);
        if (safeList.length > 0) {
          // 表单已填则保留用户输入；否则默认取首个租户
          const current = form.getFieldValue('tenantCode') as string | undefined;
          if (!current) {
            form.setFieldValue('tenantCode', safeList[0].tenantCode);
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

  const loadCaptchaPolicy = useCallback((code: string) => {
    policyRequestRef.current = code;
    setCaptchaPolicyOn(null);
    setCaptcha(null);
    authApi.captchaPolicy(code)
      .then((policy) => {
        if (policyRequestRef.current === code) {
          setCaptchaPolicyOn(policy.captchaEnabled === true);
        }
      })
      .catch(() => {
        // 策略查询失败（网络/5xx）→ fail-closed 视为开启（契约确认记录②，与后端语义一致）
        if (policyRequestRef.current === code) {
          setCaptchaPolicyOn(true);
        }
      });
  }, []);

  useEffect(() => {
    loadTenants();
  }, [loadTenants]);

  // 租户切换（含默认租户确定）→ 重新查询策略；无选中租户 → 策略回到未定态
  useEffect(() => {
    if (tenantCode) {
      loadCaptchaPolicy(tenantCode);
    } else {
      policyRequestRef.current = '';
      setCaptchaPolicyOn(null);
      setCaptcha(null);
    }
  }, [tenantCode, loadCaptchaPolicy]);

  // 页脚版权（REQ-20261117-025200）：随所选租户独立查询 public-config（不影响既有验证码策略链路）；
  // 请求失败静默不显示（页脚非关键面，不进 tenantsError/Alert）
  useEffect(() => {
    if (!tenantCode) {
      footerRequestRef.current = '';
      setFooterConfig(null);
      return;
    }
    footerRequestRef.current = tenantCode;
    authApi.publicConfig(tenantCode)
      .then((config) => {
        if (footerRequestRef.current === tenantCode) {
          setFooterConfig(config);
        }
      })
      .catch(() => {
        if (footerRequestRef.current === tenantCode) {
          setFooterConfig(null);
        }
      });
  }, [tenantCode]);

  // 策略开 → 请求图形验证码；策略关/未定 → 不请求图形码接口（契约确认记录③）
  useEffect(() => {
    if (captchaPolicyOn) {
      loadCaptcha();
    } else {
      setCaptcha(null);
    }
  }, [captchaPolicyOn, loadCaptcha]);

  const refreshCaptcha = useCallback(() => {
    setError(null);
    loadCaptcha();
  }, [loadCaptcha]);

  const onFinish = (values: LoginFormValues) => {
    // fail-closed：策略未定为关一律要求验证码（含策略查询在途）
    const captchaRequired = captchaPolicyOn !== false;
    if (captchaRequired && !captcha) {
      setError(t('login.errors.captchaNotReady'));
      return;
    }
    setSubmitting(true);
    setError(null);
    authApi.login({
      tenantCode: values.tenantCode,
      username: values.username,
      password: values.password,
      // 策略关 → 提交体不含 captchaId/captchaCode（契约确认记录③）
      ...(captchaRequired && captcha ? { captchaId: captcha.captchaId, captchaCode: values.captchaCode } : {}),
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
        // 登录成功后刷新验证码（一次性消费已完成，避免回退页复用；策略关不请求验证码接口）
        if (captchaPolicyOn) {
          loadCaptcha();
        }
        navigate('/', { replace: true });
      })
      .catch((ex: unknown) => {
        setError(ex instanceof ApiError ? ex.message : t('login.errors.loginFailed'));
        if (captchaPolicyOn) {
          loadCaptcha();
        }
      })
      .finally(() => setSubmitting(false));
  };

  /** 扫码登录轮询到 confirmed（REQ-20261119-040838 AC-6）：写入确认者 B 端会话并跳转主界面。
   *  AdminTokenIssued wire 不携带 tenantCode（租户由服务端令牌 claims 承载）→ 前端落 'default'
   *  占位（会话恢复校验要求非空），主界面画像加载后呈现真实租户。 */
  const handleQrConfirmed = useCallback((issued: TokenIssued) => {
    useAuthStore.getState().setSession({
      accessToken: issued.accessToken,
      expiresAtMs: Date.parse(issued.expiresAt),
      userId: issued.userId,
      username: issued.username,
      nickname: issued.nickname,
      tenantCode: 'default',
    }, false);
    message.success(t('login.welcome', { name: issued.nickname || issued.username }));
    navigate('/', { replace: true });
  }, [message, navigate, t]);

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
            {/* 账密/扫码双 Tab（REQ-20261119-040838）：非活跃面板默认不渲染（antd Tabs 惰性挂载） */}
            <Tabs
              defaultActiveKey="password"
              items={[
                {
                  key: 'password',
                  label: t('login.qr.passwordTab'),
                  children: (
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
                          {/* 验证码策略关（REQ-20261117-011514）→ 不渲染验证码输入框；preserve=false
                              保证租户切换卸载时清残留输入 */}
                          {captchaPolicyOn && (
                            <Form.Item
                              name="captchaCode"
                              label={t('login.form.captcha')}
                              rules={[
                                { required: true, message: t('login.form.captchaRequired') },
                                { min: 4, max: 4, message: t('login.form.captchaLength') },
                              ]}
                              style={{ marginBottom: 12 }}
                              preserve={false}
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
                          )}
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
                  ),
                },
                {
                  key: 'qr',
                  label: t('login.qr.tab'),
                  children: <QrLoginPanel onConfirmed={handleQrConfirmed} />,
                },
              ]}
            />
          </Card>
        </div>
        {/* 登录页页脚版权（REQ-20261117-025200 AC-10）：所选租户 public-config 显隐，
            visible 且文案非空才渲染（空串/null 不渲染空标签） */}
        {footerConfig?.footerVisible && footerConfig.footerCopyright ? (
          <footer className="app-foot" style={{ marginTop: 24 }}>{footerConfig.footerCopyright}</footer>
        ) : null}
      </div>
    </div>
  );
}
