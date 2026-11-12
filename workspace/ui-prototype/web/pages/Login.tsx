import { useState } from 'react';
import { Alert, App, Button, Card, Checkbox, Divider, Form, Input, Space, Tag, Typography } from 'antd';
import { GlobalOutlined, LockOutlined, MobileOutlined, ReloadOutlined, SafetyCertificateOutlined, ThunderboltOutlined, UserOutlined } from '@ant-design/icons';
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

interface LoginForm {
  account: string;
  password: string;
}

/** L1 登录页（Web 形态）：独立整页（不套应用 Layout）——桌面左品牌右表单、窄屏单列。
 *  状态演示：加载 / 错误（验证码与口令）/ 连续失败锁定（对齐后端 LoginGuard 口径）/ 成功提示。
 *  交互仅本地生效；正式契约见 platform-auth /app/v1/auth/tokens（验证码 / 登录 / 刷新）。 */
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

  const onFinish = ({ account, password }: LoginForm) => {
    setError(null);
    if (captchaInput.trim().toUpperCase() !== captcha) {
      fail('验证码不正确');
      return;
    }
    setLoading(true);
    window.setTimeout(() => {
      setLoading(false);
      if (password === DEMO_ERROR_PASSWORD) {
        fail('账号或密码不正确');
        return;
      }
      message.success(`原型演示：${account || 'demo'} 登录成功（本地生效，不落库；正式接 /app/v1/auth/tokens 签发 JWS 令牌）`);
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
      desc="独立整页登录形态：品牌区 / 表单 / 图形验证码 / 记住我；加载、错误、锁定、成功四态可走查。"
    >
      <Section title="设计说明">
        <HintText>
          登录页为独立整页（不进应用 Layout）：桌面左品牌右表单，窄屏单列。网关公开豁免仅登录端点，
          其余路由强制验签；连续失败锁定口径与后端 LoginGuard 对齐。演示规则：密码输
          <Text code>wrong</Text>
          体验失败态，其余任意账号 + 密码 ≥ 6 位即成功。
        </HintText>
      </Section>
      <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap', alignItems: 'stretch' }}>
        <div
          style={{
            flex: '1 1 300px',
            minWidth: 280,
            minHeight: 400,
            borderRadius: 14,
            padding: 28,
            color: '#fff',
            display: 'flex',
            flexDirection: 'column',
            justifyContent: 'space-between',
            background: 'linear-gradient(160deg, var(--accent, #14b8a6) 0%, #0b3d33 72%)',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <ThunderboltOutlined style={{ fontSize: 30 }} />
            <div>
              <div style={{ fontSize: 22, fontWeight: 900 }}>ACME 交付台</div>
              <div style={{ opacity: 0.82, fontSize: 12, letterSpacing: 2 }}>FRONTEND OPS · 多端发布流程演示台</div>
            </div>
          </div>
          <div style={{ display: 'grid', gap: 14, marginBlock: 22 }}>
            <Space align="start"><GlobalOutlined style={{ marginTop: 3 }} /><Text style={{ color: '#fff' }}>Web 端页面唯一来源，业务与视觉单点维护</Text></Space>
            <Space align="start"><SafetyCertificateOutlined style={{ marginTop: 3 }} /><Text style={{ color: '#fff' }}>网关集中验签，公开端点仅登录族最小集</Text></Space>
            <Space align="start"><MobileOutlined style={{ marginTop: 3 }} /><Text style={{ color: '#fff' }}>移动端共享业务层，登录形态与 Web 同源</Text></Space>
          </div>
          <Text style={{ opacity: 0.75, fontSize: 12 }}>演示环境 · 演示数据 · 不落库 · 生产接 platform-auth 签发 JWS</Text>
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
          <Form form={form} layout="vertical" onFinish={onFinish} initialValues={{ account: 'demo', remember: true }} disabled={locked}>
            <Form.Item name="account" label="账号" rules={[{ required: true, message: '请输入账号' }]}>
              <Input prefix={<UserOutlined style={{ color: 'var(--muted)' }} />} placeholder="账号 / 工号" autoComplete="username" />
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
              <Checkbox checked={remember} onChange={(e) => setRemember(e.target.checked)}>记住我（7 天免登录，演示）</Checkbox>
              <Tag>忘记密码 → 管理员重置</Tag>
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
              <Text type="secondary" style={{ fontSize: 12 }}>账号策略 / 会话撤销 / 双端前缀，见 BACKEND.md 与网关公开豁免清单</Text>
            </div>
          </Form>
        </Card>
      </div>
    </PageShell>
  );
}
