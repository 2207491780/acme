import { useCallback, useEffect, useState } from 'react';
import { Alert, App, Button, Card, Checkbox, Divider, Form, Input, Select, Space, Typography } from 'antd';
import { ApartmentOutlined, GlobalOutlined, LockOutlined, ReloadOutlined, SafetyCertificateOutlined, ThunderboltOutlined, UserOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { ApiError } from '../api/client';
import { authApi, type CaptchaData, type TenantSummary } from '../api/auth';
import { useAuthStore } from '../store/authStore';

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
 *  成功后会话写入 authStore 并跳转概览（记住我 → localStorage 持久化）。 */
export function LoginPage() {
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
        setTenantsError(ex instanceof ApiError ? ex.message : '租户清单加载失败，请检查网络');
      })
      .finally(() => setTenantsLoading(false));
  }, [form]);

  const loadCaptcha = useCallback(() => {
    setCaptchaLoading(true);
    authApi.captcha()
      .then(setCaptcha)
      .catch((ex: unknown) => {
        setCaptcha(null);
        setError(ex instanceof ApiError ? ex.message : '验证码加载失败，请点击重试');
      })
      .finally(() => setCaptchaLoading(false));
  }, []);

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
      setError('验证码未就绪，请点击刷新');
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
        message.success(`欢迎回来，${issued.nickname || issued.username}`);
        // 登录成功后刷新验证码（一次性消费已完成，避免回退页复用）
        loadCaptcha();
        navigate('/', { replace: true });
      })
      .catch((ex: unknown) => {
        setError(ex instanceof ApiError ? ex.message : '登录失败，请稍后重试');
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
            <div style={{ fontSize: 18, fontWeight: 800, color: 'var(--text)' }}>Acme SaaS 云平台</div>
            <div style={{ fontSize: 11, letterSpacing: 2, color: 'var(--muted)' }}>
              MULTI-TENANT CLOUD · 多租户发布流程云平台
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
              <Space align="start"><ApartmentOutlined style={{ marginTop: 3 }} /><Text style={{ color: '#fff' }}>租户隔离：数据 / 配置 / 配额按租户硬隔离，互不可见</Text></Space>
              <Space align="start"><SafetyCertificateOutlined style={{ marginTop: 3 }} /><Text style={{ color: '#fff' }}>网关集中验签：JWS claims 携带 tenantId，跨租户不可越权</Text></Space>
              <Space align="start"><GlobalOutlined style={{ marginTop: 3 }} /><Text style={{ color: '#fff' }}>多端同源：Web / Electron / 移动端共享业务层与租户身份</Text></Space>
            </div>
            <div>
              <Text style={{ color: '#fff', opacity: 0.85, fontSize: 13 }}>
                访问本平台需租户管理员开通账号；登录即代表同意平台使用规范。
              </Text>
              <div style={{ marginTop: 8 }}>
                <Text style={{ color: '#fff', opacity: 0.6, fontSize: 12 }}>
                  会话为 30 分钟短会话，前端自动滑动续约；登出即刻失效。
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
                action={<Button size="small" onClick={loadTenants}>重试</Button>}
              />
            )}
            <Form<LoginFormValues>
              form={form}
              layout="vertical"
              onFinish={onFinish}
              initialValues={{ remember: true }}
              requiredMark
            >
              <Form.Item
                name="tenantCode"
                label="租户"
                rules={[{ required: true, message: '请选择租户' }]}
              >
                <Select
                  showSearch
                  placeholder={tenantsLoading ? '租户清单加载中…' : '选择租户'}
                  optionFilterProp="label"
                  options={tenants.map((t) => ({ value: t.tenantCode, label: `${t.tenantName}（${t.tenantCode}）` }))}
                  loading={tenantsLoading}
                  notFoundContent={tenantsLoading ? '加载中…' : '暂无可用租户'}
                  aria-label="租户"
                />
              </Form.Item>
              <Form.Item
                name="username"
                label="账号"
                rules={[
                  { required: true, message: '请输入账号' },
                  { min: 3, max: 64, message: '账号长度 3–64 字符' },
                ]}
              >
                <Input prefix={<UserOutlined style={{ color: 'var(--muted)' }} />} placeholder="账号（租户内唯一）" autoComplete="username" />
              </Form.Item>
              <Form.Item
                name="password"
                label="密码"
                rules={[
                  { required: true, message: '请输入密码' },
                  { min: 1, max: 64, message: '密码长度 1–64 字符' },
                ]}
              >
                <Input.Password prefix={<LockOutlined style={{ color: 'var(--muted)' }} />} placeholder="密码" autoComplete="current-password" />
              </Form.Item>
              <Form.Item
                name="captchaCode"
                label="图形验证码"
                rules={[
                  { required: true, message: '请输入验证码' },
                  { min: 4, max: 4, message: '验证码为 4 位字符' },
                ]}
                style={{ marginBottom: 12 }}
              >
                <Space.Compact style={{ width: '100%' }}>
                  <Input placeholder="输入右侧 4 位字符" maxLength={4} aria-label="图形验证码" />
                  <Button
                    onClick={refreshCaptcha}
                    style={{ width: 118, paddingInline: 8, userSelect: 'none' }}
                    title="点击刷新验证码（并重置演示锁定计数）"
                    disabled={captchaLoading}
                  >
                    {captcha
                      ? (
                        <img
                          src={captcha.imageBase64Data}
                          alt="图形验证码"
                          style={{ height: 26, display: 'block' }}
                        />
                      )
                      : <ReloadOutlined />}
                    <ReloadOutlined style={{ marginLeft: 4, color: 'var(--muted)' }} />
                  </Button>
                </Space.Compact>
              </Form.Item>
              <Form.Item name="remember" valuePropName="checked" style={{ marginBottom: 18 }}>
                <Checkbox>记住我（7 天内自动保持登录）</Checkbox>
              </Form.Item>
              <Button block type="primary" htmlType="submit" loading={submitting} icon={<ThunderboltOutlined />}>
                登 录
              </Button>
              <Divider plain style={{ marginBlock: 14 }}>
                <Text type="secondary" style={{ fontSize: 12 }}>会话说明</Text>
              </Divider>
              <Text type="secondary" style={{ fontSize: 12, display: 'block', textAlign: 'center' }}>
                登录签发 JWS 访问令牌（30 分钟短会话），前端自动滑动续约；
                连续失败将触发账号锁定保护。
              </Text>
            </Form>
          </Card>
        </div>
      </div>
    </div>
  );
}
