import { useState } from 'react';
import { App as AntApp, Button, Input, Typography } from 'antd';
import { LockOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { useLockStore, useMeStore } from '@acme/core';

const { Text, Title } = Typography;

/**
 * 锁屏全屏遮罩（REQ-20261114-120112，AC-4）：
 * - locked=false 渲染 null（遮罩仅锁屏态在场）；
 * - 遮罩根节点 inline style position:fixed 覆盖内容区；
 * - 口令解锁走 useLockStore（本地会话口令，非后端校验）；失败经 AntApp message 提示；
 * - 忘记口令 = 清除本地口令（useLockStore.forgotPasscode）+ 清除用户会话（useMeStore）→ 重新登录（#/login）。
 */
export function LockScreenOverlay() {
  const locked = useLockStore((s) => s.locked);
  const { t } = useTranslation();
  const { message } = AntApp.useApp();
  const navigate = useNavigate();
  const [passcode, setPasscode] = useState('');

  if (!locked) {
    return null;
  }

  const onUnlock = () => {
    const result = useLockStore.getState().unlock(passcode);
    if (result.ok) {
      setPasscode('');
      return;
    }
    message.error(result.message ?? t('components.lockScreen.unlockFailed'));
  };

  const onForgot = () => {
    // 清除本地口令并清除登录会话 → 重新登录（与后端无交互）
    useLockStore.getState().forgotPasscode();
    useMeStore.getState().clear();
    navigate('/login');
  };

  return (
    <div
      style={{
        position: 'fixed',
        inset: 0,
        zIndex: 1000,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'color-mix(in srgb, var(--bg-0) 82%, transparent)',
        backdropFilter: 'blur(8px)',
      }}
    >
      <div
        style={{
          width: 320,
          padding: 24,
          borderRadius: 12,
          border: '1px solid var(--border)',
          background: 'var(--surface)',
          boxShadow: 'var(--shadow-card)',
          textAlign: 'center',
        }}
      >
        <LockOutlined style={{ fontSize: 28, color: 'var(--accent)' }} />
        <Title level={4} style={{ margin: '12px 0 4px' }}>
          {t('components.lockScreen.title')}
        </Title>
        <Text type="secondary">{t('components.lockScreen.desc')}</Text>
        <Input
          type="password"
          autoFocus
          value={passcode}
          onChange={(e) => setPasscode(e.target.value)}
          onPressEnter={onUnlock}
          placeholder={t('components.lockScreen.placeholder')}
          style={{ marginTop: 16 }}
          aria-label={t('components.lockScreen.placeholder')}
        />
        <Button type="primary" block style={{ marginTop: 12 }} onClick={onUnlock}>
          {t('components.lockScreen.unlock')}
        </Button>
        <Button type="link" block style={{ marginTop: 4 }} onClick={onForgot}>
          {t('components.lockScreen.forgot')}
        </Button>
      </div>
    </div>
  );
}
