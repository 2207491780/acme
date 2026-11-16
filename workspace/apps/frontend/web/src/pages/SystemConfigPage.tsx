import { useEffect, useState } from 'react';
import { App, Button, Card, Form, Select, Space, Switch, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { ControlOutlined, ReloadOutlined, SaveOutlined } from '@ant-design/icons';
import { ApiError, useMeStore, useSystemConfigStore } from '@acme/core';
import { PageHead } from '../components/PageHead';

const { Text } = Typography;

interface SystemConfigFormValues {
  watermarkEnabled: boolean;
  lockIdleTimeoutMs: number;
}

/**
 * 系统配置（REQ-20261116-230354，AC-5/AC-6）：租户级安全策略管理页。
 * GET 认证即可读（页面不设权限门禁）；保存能力按 system:config:update fail-closed——
 * 无权限时表单只读且不渲染保存按钮。保存经 useSystemConfigStore.update（version 乐观锁），
 * 成功即时联动全局水印/闲置锁屏；冲突透出后端文案并提示刷新重试。
 */
export function SystemConfigPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const permissions = useMeStore((s) => s.me?.permissions) ?? [];
  const canUpdate = permissions.includes('system:config:update');

  const watermarkEnabled = useSystemConfigStore((s) => s.watermarkEnabled);
  const lockIdleTimeoutMs = useSystemConfigStore((s) => s.lockIdleTimeoutMs);
  const version = useSystemConfigStore((s) => s.version);

  const [form] = Form.useForm<SystemConfigFormValues>();
  const [submitting, setSubmitting] = useState(false);

  // 进入页面即取最新配置与版本（引导已加载过也重取，保证乐观锁版本新鲜）
  useEffect(() => {
    void useSystemConfigStore.getState().load();
  }, []);

  // store → 表单同步（引导加载/保存成功/手动刷新后取最新值与版本）
  useEffect(() => {
    form.setFieldsValue({ watermarkEnabled, lockIdleTimeoutMs });
  }, [form, watermarkEnabled, lockIdleTimeoutMs, version]);

  const submit = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      await useSystemConfigStore.getState().update({ ...values, version });
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
        <Form form={form} layout="vertical" disabled={!canUpdate}>
          <Form.Item
            name="watermarkEnabled"
            label={t('systemConfig.watermark')}
            valuePropName="checked"
            extra={t('systemConfig.watermarkDesc')}
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
        </Form>
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
    </>
  );
}