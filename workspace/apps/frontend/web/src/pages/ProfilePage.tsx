import { useEffect, useRef, useState } from 'react';
import { App, Button, Card, Col, Form, Input, List, Row, Space, Switch, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import {
  CloudUploadOutlined,
  IdcardOutlined,
  NotificationOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  UserOutlined,
} from '@ant-design/icons';
import {
  ApiError,
  authApi,
  formatDate,
  systemApi,
  uploadErrorMessage,
  useMeStore,
  type SelfSessionView,
  type UserPreferenceView,
} from '@acme/core';
import { PageHead } from '../components/PageHead';
import { Avatar } from '../components/Avatar';

const { Text } = Typography;

/**
 * 个人中心（REQ-20261119-082531 契约⑦）：/profile 独立页（认证即可，无权限门禁），
 * 承接账号域三卡——资料（Avatar 上传/预览 + nickname/email/phone 表单）、安全（自助改密 +
 * 在线会话管理）、通知（messagePush 偏好）；桌面更新卡留设置页不动（契约：Updater 卡留 Settings）。
 * 入口：顶栏用户菜单「个人中心」+ NAV 条目（routes.tsx /profile）。
 */

/** 资料卡：头像两步上传（POST /admin/v1/infra/me/avatar → 预览 → 随表单 PUT /me 提交 avatarUrl）。 */
function ProfileCard() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const me = useMeStore((s) => s.me);
  const [form] = Form.useForm();
  const fileRef = useRef<HTMLInputElement>(null);
  const [avatarUrl, setAvatarUrl] = useState<string | null>(null);
  const [avatarDirty, setAvatarDirty] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [saving, setSaving] = useState(false);

  // me 异步就绪/刷新后回填表单与头像（本地刚上传未保存的头像优先展示）
  useEffect(() => {
    if (!me) {
      return;
    }
    form.setFieldsValue({
      nickname: me.user.nickname ?? '',
      email: me.user.email ?? '',
      phone: me.user.phone ?? '',
    });
    if (!avatarDirty) {
      setAvatarUrl(me.user.avatarUrl ?? null);
    }
  }, [me, form, avatarDirty]);

  const onAvatarPicked = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) {
      return;
    }
    setUploading(true);
    systemApi.meAvatarUpload(file)
      .then((view) => {
        if (!view.url) {
          throw new Error(t('common.errors.operationFailed'));
        }
        setAvatarUrl(view.url);
        setAvatarDirty(true);
        message.success(t('profile.toasts.avatarUploaded'));
      })
      .catch((ex: unknown) => {
        message.error(uploadErrorMessage(ex, t('common.errors.operationFailed')));
      })
      .finally(() => setUploading(false));
  };

  const onSave = (values: { nickname: string; email?: string; phone?: string }) => {
    setSaving(true);
    systemApi.meUpdate({
      nickname: values.nickname.trim(),
      email: values.email?.trim() ? values.email.trim() : null,
      phone: values.phone?.trim() ? values.phone.trim() : null,
      avatarUrl: avatarUrl,
    })
      .then(() => {
        setAvatarDirty(false); // 服务端已落库，后续 me 刷新直接回显
        message.success(t('profile.toasts.profileSaved'));
        void useMeStore.getState().load();
      })
      .catch((ex: unknown) => {
        // PROFILE_INVALID=platform-system-13-0001（REQ-20261128-051442 附录 A.3 全码）
        message.error(ex instanceof ApiError && ex.code === 'platform-system-13-0001'
          ? t('profile.errors.profileInvalid')
          : ex instanceof Error ? ex.message : t('common.errors.operationFailed'));
      })
      .finally(() => setSaving(false));
  };

  return (
    <Card title={<Space><IdcardOutlined style={{ color: 'var(--accent)' }} /> {t('profile.cards.profile')}</Space>} style={{ height: '100%' }}>
      <Space align="center" size={16} style={{ marginBottom: 16 }}>
        <Avatar avatarUrl={avatarUrl} name={me?.user.nickname || me?.user.username || ''} size={72} />
        <Space direction="vertical" size={4}>
          <Button
            icon={<CloudUploadOutlined />}
            loading={uploading}
            onClick={() => fileRef.current?.click()}
            aria-label={t('profile.actions.uploadAvatar')}
          >
            {t('profile.actions.uploadAvatar')}
          </Button>
          <Text type="secondary" style={{ fontSize: 12 }}>{me?.user.username}</Text>
        </Space>
        <input
          ref={fileRef}
          type="file"
          accept="image/*"
          hidden
          onChange={onAvatarPicked}
          aria-hidden="true"
          tabIndex={-1}
        />
      </Space>
      <Form form={form} layout="vertical" onFinish={onSave}>
        <Form.Item
          name="nickname"
          label={t('profile.fields.nickname')}
          rules={[{ required: true, message: t('profile.errors.profileInvalid') }]}
        >
          <Input maxLength={32} aria-label={t('profile.fields.nickname')} />
        </Form.Item>
        <Form.Item name="email" label={t('profile.fields.email')}>
          <Input type="email" aria-label={t('profile.fields.email')} />
        </Form.Item>
        <Form.Item name="phone" label={t('profile.fields.phone')}>
          <Input aria-label={t('profile.fields.phone')} />
        </Form.Item>
        <Button type="primary" htmlType="submit" loading={saving} aria-label={t('profile.actions.saveProfile')}>
          {t('profile.actions.saveProfile')}
        </Button>
      </Form>
    </Card>
  );
}

/** 安全卡：自助改密（成功提示其他设备已下线）+ 在线会话列表（当前标记 / 逐条下线 / 下线其他）。 */
function SecurityCard() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const [form] = Form.useForm();
  const [sessions, setSessions] = useState<SelfSessionView[]>([]);
  const [sessionsLoading, setSessionsLoading] = useState(false);
  const [kicking, setKicking] = useState<string | null>(null);
  const [kickingOthers, setKickingOthers] = useState(false);
  const [changing, setChanging] = useState(false);

  const loadSessions = () => {
    setSessionsLoading(true);
    authApi.selfSessions()
      .then((rows) => setSessions(Array.isArray(rows) ? rows : []))
      .catch(() => setSessions([]))
      .finally(() => setSessionsLoading(false));
  };
  useEffect(loadSessions, []);

  const onChangePassword = (values: { oldPassword: string; newPassword: string; confirmPassword: string }) => {
    if (values.newPassword !== values.confirmPassword) {
      message.error(t('profile.toasts.passwordMismatch'));
      return;
    }
    setChanging(true);
    authApi.selfPassword({ oldPassword: values.oldPassword, newPassword: values.newPassword })
      .then(() => {
        message.success(t('profile.toasts.passwordChanged'));
        form.resetFields();
        loadSessions(); // 其他会话已被服务端 KICKED，刷新列表
      })
      .catch((ex: unknown) => {
        // 改密域全码（REQ-20261128-051442 附录 A.4）：OLD_PASSWORD_INVALID=07-0001 / SAME_PASSWORD=07-0002
        if (ex instanceof ApiError && ex.code === 'platform-auth-07-0001') {
          message.error(t('profile.errors.oldPasswordInvalid'));
          return;
        }
        if (ex instanceof ApiError && ex.code === 'platform-auth-07-0002') {
          message.error(t('profile.errors.samePassword'));
          return;
        }
        message.error(ex instanceof Error ? ex.message : t('common.errors.operationFailed'));
      })
      .finally(() => setChanging(false));
  };

  const onKick = (tokenId: string) => {
    setKicking(tokenId);
    authApi.selfKickSession(tokenId)
      .then(() => {
        message.success(t('profile.toasts.sessionKicked'));
        return loadSessionsInPlace(setSessions);
      })
      .catch((ex: unknown) => {
        message.error(ex instanceof Error ? ex.message : t('common.errors.operationFailed'));
      })
      .finally(() => setKicking(null));
  };

  const onKickOthers = () => {
    setKickingOthers(true);
    authApi.selfKickOthers()
      .then(() => {
        message.success(t('profile.toasts.othersKicked'));
        return loadSessionsInPlace(setSessions);
      })
      .catch((ex: unknown) => {
        message.error(ex instanceof Error ? ex.message : t('common.errors.operationFailed'));
      })
      .finally(() => setKickingOthers(false));
  };

  return (
    <Card title={<Space><SafetyCertificateOutlined style={{ color: 'var(--accent)' }} /> {t('profile.cards.security')}</Space>} style={{ height: '100%' }}>
      <Form form={form} layout="vertical" onFinish={onChangePassword}>
        <Form.Item
          name="oldPassword"
          label={t('profile.fields.oldPassword')}
          rules={[{ required: true, message: t('profile.errors.profileInvalid') }]}
        >
          <Input.Password aria-label={t('profile.fields.oldPassword')} />
        </Form.Item>
        <Form.Item
          name="newPassword"
          label={t('profile.fields.newPassword')}
          rules={[{ required: true, message: t('profile.errors.profileInvalid') }]}
          extra={t('profile.toasts.passwordPlaceholder')}
        >
          <Input.Password aria-label={t('profile.fields.newPassword')} />
        </Form.Item>
        <Form.Item
          name="confirmPassword"
          label={t('profile.fields.confirmPassword')}
          rules={[{ required: true, message: t('profile.errors.profileInvalid') }]}
        >
          <Input.Password aria-label={t('profile.fields.confirmPassword')} />
        </Form.Item>
        <Button type="primary" htmlType="submit" loading={changing} aria-label={t('profile.actions.changePassword')}>
          {t('profile.actions.changePassword')}
        </Button>
      </Form>

      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', margin: '22px 0 8px' }}>
        <Text strong>{t('profile.session.listTitle')}</Text>
        <Space size={8}>
          <Button size="small" icon={<ReloadOutlined />} onClick={loadSessions} aria-label={t('profile.actions.refreshSessions')}>
            {t('profile.actions.refreshSessions')}
          </Button>
          <Button
            size="small"
            danger
            loading={kickingOthers}
            onClick={onKickOthers}
            aria-label={t('profile.actions.kickOthers')}
          >
            {t('profile.actions.kickOthers')}
          </Button>
        </Space>
      </div>
      <List
        size="small"
        loading={sessionsLoading}
        dataSource={sessions}
        locale={{ emptyText: t('profile.session.empty') }}
        renderItem={(item) => (
          <List.Item
            actions={item.current
              ? undefined
              : [
                <Button
                  key="kick"
                  size="small"
                  danger
                  loading={kicking === item.tokenId}
                  onClick={() => onKick(item.tokenId)}
                  aria-label={`${t('profile.actions.kickSession')} ${item.tokenId}`}
                >
                  {t('profile.actions.kickSession')}
                </Button>,
              ]}
          >
            <List.Item.Meta
              avatar={<UserOutlined style={{ color: 'var(--accent)' }} />}
              title={(
                <Space size={8}>
                  <span style={{ fontFamily: 'var(--mono)', fontSize: 12 }}>{item.tokenId}</span>
                  {item.current && <Tag color="green">{t('profile.session.current')}</Tag>}
                </Space>
              )}
              description={(
                <Text type="secondary" style={{ fontSize: 12 }}>
                  {t('profile.fields.sessionIp')}：{item.ip} · {t('profile.fields.sessionIssuedAt')}：{formatDate(Date.parse(item.issuedAt))} · {t('profile.fields.sessionLastActiveAt')}：{formatDate(Date.parse(item.lastActiveAt))}
                </Text>
              )}
            />
          </List.Item>
        )}
      />
    </Card>
  );
}

/** 会话列表原位刷新（kick 成功后拉取最新集合，不整卡 loading 闪烁）。 */
function loadSessionsInPlace(setSessions: (rows: SelfSessionView[]) => void): Promise<void> {
  return authApi.selfSessions()
    .then((rows) => setSessions(Array.isArray(rows) ? rows : []))
    .catch(() => undefined);
}

/** 通知卡：messagePush 偏好开关（本人行 upsert；失败回滚开关态）。 */
function NotificationCard() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const [prefs, setPrefs] = useState<UserPreferenceView | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let alive = true;
    systemApi.mePreferences()
      .then((view) => {
        if (alive) setPrefs(view);
      })
      .catch(() => {
        // GET 失败兜底 = 两域缺省视图（REQ-20261121-200415 契约④：messagePush=false+themePreference=null）
        if (alive) setPrefs({ messagePush: false, themePreference: null });
      });
    return () => {
      alive = false;
    };
  }, []);

  const onToggle = (checked: boolean) => {
    setSaving(true);
    systemApi.mePreferencesUpdate({ messagePush: checked })
      .then((view) => {
        // 端点理论回传最新视图；空体/空数据兜底本地开关态，避免成功后开关回弹
        //（两域形状：已加载视图保留 themePreference 原值，未加载按契约④缺省兜底）
        setPrefs(view ?? (prefs ? { ...prefs, messagePush: checked } : { messagePush: checked, themePreference: null }));
        message.success(t('profile.toasts.prefsSaved'));
      })
      .catch((ex: unknown) => {
        setPrefs((cur) => (cur ? { ...cur, messagePush: !checked } : cur)); // 失败回滚
        message.error(ex instanceof Error ? ex.message : t('common.errors.operationFailed'));
      })
      .finally(() => setSaving(false));
  };

  return (
    <Card title={<Space><NotificationOutlined style={{ color: 'var(--accent)' }} /> {t('profile.cards.notification')}</Space>} style={{ height: '100%' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, padding: '14px 0' }}>
        <div>
          <Text strong>{t('profile.notification.messagePush')}</Text>
          <div><Text type="secondary" style={{ fontSize: 12 }}>{t('profile.notification.messagePushDesc')}</Text></div>
        </div>
        <Switch
          checked={prefs?.messagePush === true}
          loading={saving || prefs === null}
          onChange={onToggle}
          aria-label={t('profile.notification.messagePush')}
        />
      </div>
    </Card>
  );
}

export function ProfilePage() {
  const { t } = useTranslation();
  return (
    <>
      <PageHead eyebrow={t('profile.head.eyebrow')} title={t('profile.title')} sub={t('profile.head.sub')} />
      <Row gutter={[18, 18]}>
        <Col xs={24} lg={12}>
          <ProfileCard />
        </Col>
        <Col xs={24} lg={12}>
          <SecurityCard />
        </Col>
        <Col xs={24} lg={12}>
          <NotificationCard />
        </Col>
      </Row>
    </>
  );
}
