import { useState } from 'react';
import { Alert, App, Button, Card, Checkbox, Divider, Form, Input, Space, Tag, Typography } from 'antd';
import { ApartmentOutlined, GlobalOutlined, LockOutlined, ReloadOutlined, SafetyCertificateOutlined, ThunderboltOutlined, UserOutlined } from '@ant-design/icons';
import { PageShell } from '../shell/PageShell';
import { HintText, Section } from '../components/blocks';

const { Text } = Typography;

/** 演示验证码：4 位无易混字符，本地生成不落库。 */
function makeCaptcha(): string {
  const chars = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
  let out = '';
  for (let i = 0; i < 4; i += 1) out += chars[Math.floor(Math.random() * chars.length)];
  return out;
}

const MAX_ATTEMPTS = 5;
const DEMO_ERROR_PASSWORD = 'wrong';
const DEMO_MISSING_TENANT = 'nope';

interface LoginForm {
  tenant: string;
  account: string;
  password: string;
}

/** L1 登录页（Web 形态 · Acme SaaS 云平台 · 多租户）：独立整页（不套应用 Layout）——
 *  桌面左品牌右表单、窄屏单列。租户代码前置，JWS claims 携带 tenantId。
 *  状态演示：加载 / 错误（验证码、租户、口令）/ 连续失败锁定（对齐后端 LoginGuard 口径）/ 成功提示。
 *  交互仅本地生效；正式契约见 platform-auth /app/v1/auth/tokens 与租户初始化 /admin/v1/system/tenants/init。 */
export default function Login() {
  const { message } = App.useApp();
  const [captcha, setCaptcha] = useState(makeCaptcha);
  const [captchaInput, setCaptchaInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [attempts, setAttempts] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [remember, setRemember] = useState(true);
  const [form] = Form.useForm<LoginForm>();

  const locked = attempts >= MAX_ATTEMPTS;
  const remaining = MAX_ATTEMPTS - attempts;

  const refreshCaptcha = () => {
    setCaptcha(makeCaptcha());
    setCaptchaInput('');
    setAttempts(0);
    setError(null);
  };

  const fail = (why: string) => {
    const left = Math.max(remaining - 1, 0);
    setAttempts((n) => Math.min(n + 1, MAX_ATTEMPTS));
    setError(`${why}（演示；连续失败 ${MAX_ATTEMPTS} 次锁定，剩余尝试 ${left} 次）`);
    setCaptcha(makeCaptcha());
    setCaptchaInput('');
  };

  const onFinish = ({ tenant, account, password }: LoginForm) => {
    setError(null);
    if (captchaInput.trim().toUpperCase() !== captcha) {
      fail('验证码不正确');
      return;
    }
    setLoading(true);
    window.setTimeout(() => {
      setLoading(false);
      if (tenant === DEMO_MISSING_TENANT) {
        fail(`租户「${tenant}」不存在`);
        return;
      }
      if (password === DEMO_ERROR_PASSWORD) {
        fail('账号或密码不正确');
        return;
      }
      message.success(
        `原型演示：租户 ${tenant || 'acme'} · ${account || 'demo'} 登录成功（claims.tenantId=${tenant || 'acme'}，本地生效，不落库）`,
      );
    }, 1200);
  };

  const captchaBox = (
    <Button
      onClick={refreshCaptcha}
      style={{
        width: 108,
        fontFamily: 'monospace',
        fontSize: 18,
        fontWeight: 700,
        letterSpacing: 5,
        userSelect: 'none',
        textDecoration: 'line-through',
      }}
      title="点击刷新验证码（并重置演示锁定计数）"
      icon={<ReloadOutlined />}
    >
      {captcha}
    </Button>
  );

  return (
    <PageShell
      title="登录页"
      domain="认证域 L1"
      desc="Acme SaaS 云平台 · 多租户登录形态：租户代码 / 品牌区 / 表单 / 验证码 / 四态走查。"
    >
      <Section title="设计说明">
        <HintText>
          多租户口径：登录前置租户代码，签发的 JWS claims 携带 tenantId，网关与服务按租户隔离鉴权；
          租户自注册走初始化接口（/admin/v1/system/tenants/init），忘记密码由租户管理员重置。
          演示规则：密码输
          <Text code>wrong</Text>
          体验口令失败态，租户输
          <Text code>nope</Text>
          体验租户不存在态，其余任意租户 + 账号 + 密码 ≥ 6 位即成功。
        </HintText>
      </Section>
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
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <ThunderboltOutlined style={{ fontSize: 30 }} />
            <div>
              <div style={{ fontSize: 22, fontWeight: 900 }}>Acme SaaS 云平台</div>
              <div style={{ opacity: 0.82, fontSize: 12, letterSpacing: 2 }}>MULTI-TENANT CLOUD · 多租户发布流程云平台</div>
            </div>
          </div>
          <div style={{ display: 'grid', gap: 14, marginBlock: 22 }}>
            <Space align="start"><ApartmentOutlined style={{ marginTop: 3 }} /><Text style={{ color: '#fff' }}>租户隔离：数据 / 配置 / 配额按租户硬隔离，互不可见</Text></Space>
            <Space align="start"><SafetyCertificateOutlined style={{ marginTop: 3 }} /><Text style={{ color: '#fff' }}>网关集中验签：JWS claims 携带 tenantId，跨租户不可越权</Text></Space>
            <Space align="start"><GlobalOutlined style={{ marginTop: 3 }} /><Text style={{ color: '#fff' }}>多端同源：Web / Electron / 移动端共享业务层与租户身份</Text></Space>
          </div>
          <Text style={{ opacity: 0.75, fontSize: 12 }}>
            演示环境 · 演示数据 · 不落库 · 生产接 platform-auth（租户初始化 /admin/v1/system/tenants/init）
          </Text>
        </div>
        <Card style={{ flex: '1 1 340px', minWidth: 320, maxWidth: 470 }} styles={{ body: { padding: '22px 24px' } }}>
          {error && <Alert type="error" showIcon style={{ marginBottom: 16 }} message={error} />}
          {locked && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
              message="演示锁定：连续失败达上限"
              description="点击右侧验证码刷新即可重置锁定计数（正式实现为服务端 60s 冷却，见 LoginGuard）。"
            />
          )}
          <Form form={form} layout="vertical" onFinish={onFinish} initialValues={{ tenant: 'acme', account: 'demo', remember: true }} disabled={locked}>
            <Form.Item name="tenant" label="租户代码" rules={[{ required: true, message: '请输入租户代码' }]} extra={<Text type="secondary" style={{ fontSize: 12 }}>租户输 nope 体验「租户不存在」</Text>}>
              <Input prefix={<ApartmentOutlined style={{ color: 'var(--muted)' }} />} placeholder="租户代码" autoComplete="organization" />
            </Form.Item>
            <Form.Item name="account" label="账号" rules={[{ required: true, message: '请输入账号' }]}>
              <Input prefix={<UserOutlined style={{ color: 'var(--muted)' }} />} placeholder="账号（租户内唯一）" autoComplete="username" />
            </Form.Item>
            <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
              <Input.Password prefix={<LockOutlined style={{ color: 'var(--muted)' }} />} placeholder="密码（输 wrong 体验失败态）" autoComplete="current-password" />
            </Form.Item>
            <Form.Item label="图形验证码" required style={{ marginBottom: 12 }}>
              <Space.Compact style={{ width: '100%' }}>
                <Input
                  value={captchaInput}
                  onChange={(e) => setCaptchaInput(e.target.value)}
                  placeholder="输入右侧 4 位字符"
                  maxLength={4}
                  aria-label="图形验证码"
                />
                {captchaBox}
              </Space.Compact>
            </Form.Item>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 18 }}>
              <Checkbox checked={remember} onChange={(e) => setRemember(e.target.checked)}>记住租户与账号（7 天，演示）</Checkbox>
              <Tag>忘记密码 → 租户管理员重置</Tag>
            </div>
            <Button block type="primary" htmlType="submit" loading={loading} disabled={locked} icon={<ThunderboltOutlined />}>
              {loading ? '验签中…' : '登录'}
            </Button>
            <Divider plain style={{ marginBlock: 14 }}>
              <Text type="secondary" style={{ fontSize: 12 }}>其他方式（形态探索）</Text>
            </Divider>
            <div style={{ display: 'flex', justifyContent: 'center', gap: 8 }}>
              <Button size="small" disabled>企业 SSO</Button>
              <Button size="small" disabled>OAuth</Button>
              <Button size="small" disabled>LDAP</Button>
            </div>
            <div style={{ marginTop: 14, textAlign: 'center' }}>
              <Text type="secondary" style={{ fontSize: 12 }}>
                租户配额 / 数据隔离 / 会话撤销，见 BACKEND.md（platform-system 租户域）与网关验签口径
              </Text>
            </div>
          </Form>
        </Card>
      </div>
    </PageShell>
  );
}
