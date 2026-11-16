import { useEffect, useState } from 'react';
import { App, Button, Card, Form, Input, Select, Space, Switch, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { ControlOutlined, CopyrightOutlined, ReloadOutlined, SaveOutlined } from '@ant-design/icons';
import { ApiError, useMeStore, useSystemConfigStore } from '@acme/core';
import { PageHead } from '../components/PageHead';

const { Text } = Typography;

interface SystemConfigFormValues {
  watermarkEnabled: boolean;
  captchaEnabled: boolean;
  lockIdleTimeoutMs: number;
  /** 页脚版权文案（REQ-20261117-025200；表单态为字符串，提交 trim 空串→null）。 */
  footerCopyright: string;
  footerVisible: boolean;
}

/**
 * 系统配置（REQ-20261116-230354，AC-5/AC-6；REQ-20261117-011514 增登录验证码开关；
 * REQ-20261117-025200 增页脚版权卡）：
 * 租户级策略管理页。GET 认证即可读（页面不设权限门禁）；保存能力按 system:config:update
 * fail-closed——无权限时表单只读且不渲染保存按钮。保存经 useSystemConfigStore.update
 * （version 乐观锁），成功即时联动全局水印/闲置锁屏/页脚显隐；冲突透出后端文案并提示刷新重试。
 */
export function SystemConfigPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const permissions = useMeStore((s) => s.me?.permissions) ?? [];
  const canUpdate = permissions.includes('system:config:update');

  const watermarkEnabled = useSystemConfigStore((s) => s.watermarkEnabled);
  const captchaEnabled = useSystemConfigStore((s) => s.captchaEnabled);
  const lockIdleTimeoutMs = useSystemConfigStore((s) => s.lockIdleTimeoutMs);
  const footerCopyright = useSystemConfigStore((s) => s.footerCopyright);
  const footerVisible = useSystemConfigStore((s) => s.footerVisible);
  const version = useSystemConfigStore((s) => s.version);

  const [form] = Form.useForm<SystemConfigFormValues>();
  const [submitting, setSubmitting] = useState(false);

  // 进入页面即取最新配置与版本（引导已加载过也重取，保证乐观锁版本新鲜）
  useEffect(() => {
    void useSystemConfigStore.getState().load();
  }, []);

  // store → 表单同步（引导加载/保存成功/手动刷新后取最新值与版本）
  useEffect(() => {
    form.setFieldsValue({
      watermarkEnabled,
      captchaEnabled,
      lockIdleTimeoutMs,
      footerCopyright: footerCopyright ?? '',
      footerVisible,
    });
  }, [form, watermarkEnabled, captchaEnabled, lockIdleTimeoutMs, footerCopyright, footerVisible, version]);

  const submit = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      await useSystemConfigStore.getState().update({
        ...values,
        // 页脚文案归一：trim 后空串 → null（契约：null/空串均不渲染页脚）
        footerCopyright: values.footerCopyright?.trim() || null,
        version,
      });
      message.success(t('systemConfig.saved'));
    } catch (ex: unknown) {
      message.error(ex instanceof ApiError ? ex.message : t('common.errors.operationFailed'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <>
      <PageHead eyebrow={t('systemConfig.head.eyebrow')} title={t('systemConfig.head.title')} sub={t('systemConfig.head.sub')} />
      <Form form={form} layout="vertical" disabled={!canUpdate}>
        <Card
          title={<Space><ControlOutlined style={{ color: 'var(--accent)' }} /> {t('systemConfig.title')}</Space>}
          style={{ maxWidth: 640 }}
          extra={
            <Button
              icon={<ReloadOutlined />}
              onClick={() => void useSystemConfigStore.getState().load()}
              aria-label={t('systemConfig.reload')}
            >
              {t('common.actions.refresh')}
            </Button>
          }
        >
          <Form.Item
            name="watermarkEnabled"
            label={t('systemConfig.watermark')}
            valuePropName="checked"
            extra={t('systemConfig.watermarkDesc')}
          >
            <Switch />
          </Form.Item>
          <Form.Item
            name="captchaEnabled"
            label={t('systemConfig.captcha')}
            valuePropName="checked"
            extra={t('systemConfig.captchaDesc')}
          >
            <Switch />
          </Form.Item>
          <Form.Item
            name="lockIdleTimeoutMs"
            label={t('systemConfig.idleLock')}
            extra={t('systemConfig.idleLockDesc')}
          >
            <Select
              style={{ width: 200 }}
              options={[
                { value: 0, label: t('systemConfig.idleOff') },
                { value: 60_000, label: t('systemConfig.idle1') },
                { value: 300_000, label: t('systemConfig.idle5') },
                { value: 900_000, label: t('systemConfig.idle15') },
              ]}
            />
          </Form.Item>
          <Space direction="vertical" size={8} style={{ width: '100%' }}>
            {canUpdate ? (
              <Button type="primary" icon={<SaveOutlined />} loading={submitting} onClick={() => void submit()}>
                {t('common.actions.save')}
              </Button>
            ) : (
              <Text type="secondary">{t('systemConfig.readOnly')}</Text>
            )}
            <Text type="secondary" style={{ fontSize: 12 }}>
              {t('common.validation.optimisticLock', { version })}{t('common.validation.optimisticLockConflict')}
            </Text>
          </Space>
        </Card>
        {/* 页脚版权卡（REQ-20261117-025200）：文案 ≤255 + 显隐开关；同一 form/同一 PUT 乐观锁口径 */}
        <Card
          title={<Space><CopyrightOutlined style={{ color: 'var(--accent)' }} /> {t('systemConfig.footerTitle')}</Space>}
          style={{ maxWidth: 640, marginTop: 16 }}
        >
          <Form.Item
            name="footerCopyright"
            label={t('systemConfig.footerCopyright')}
            rules={[{ max: 255, message: t('common.validation.maxLength', { max: 255 }) }]}
            extra={t('systemConfig.footerCopyrightDesc')}
          >
            <Input.TextArea rows={3} maxLength={255} showCount placeholder={t('systemConfig.footerCopyrightPlaceholder')} />
          </Form.Item>
          <Form.Item
            name="footerVisible"
            label={t('systemConfig.footerVisible')}
            valuePropName="checked"
            extra={t('systemConfig.footerVisibleDesc')}
          >
            <Switch />
          </Form.Item>
        </Card>
      </Form>
    </>
  );
}
