import { Alert, Button, Space, Spin, Typography } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { QR_SIZE_PX, useQrLogin } from '../hooks/useQrLogin';
import type { QrTicketStatus, TokenIssued } from '@acme/core';

const { Text } = Typography;

/** 票据状态 → i18n 文案键（静态映射，禁动态拼键）。 */
const STATUS_KEY: Record<QrTicketStatus, string> = {
  created: 'login.qr.statusCreated',
  scanned: 'login.qr.statusScanned',
  confirmed: 'login.qr.statusConfirmed',
  expired: 'login.qr.statusExpired',
  cancelled: 'login.qr.statusCancelled',
};

/** 扫码登录面板（REQ-20261119-040838 AC-6）：二维码（qrcode 本地 dataURL，200px）+
 *  状态提示（待扫描/已扫描待确认/已登录/已过期/已取消）+「刷新二维码」
 *  （重新创建 ticket 并重启轮询）。轮询/终态/卸载停止语义在 useQrLogin。 */
export function QrLoginPanel({ onConfirmed }: { onConfirmed: (issued: TokenIssued) => void }) {
  const { t } = useTranslation();
  const { dataUrl, status, createError, creating, refresh } = useQrLogin({ onConfirmed });

  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 16, paddingTop: 8 }}>
      <Text type="secondary" style={{ fontSize: 13 }}>{t('login.qr.hint')}</Text>
      {createError && (
        <Alert type="error" showIcon style={{ width: '100%' }} message={createError} />
      )}
      <div
        style={{
          width: QR_SIZE_PX,
          height: QR_SIZE_PX,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          background: 'var(--bg-0)',
          borderRadius: 8,
        }}
      >
        {dataUrl
          ? <img src={dataUrl} alt={t('login.qr.imageAlt')} width={QR_SIZE_PX} height={QR_SIZE_PX} style={{ display: 'block' }} />
          : <Spin aria-label={creating ? t('login.qr.loading') : undefined} />}
      </div>
      <Space direction="vertical" size={4} style={{ textAlign: 'center' }}>
        {status && (
          <Text strong data-testid="qr-status">{t(STATUS_KEY[status])}</Text>
        )}
        <Button icon={<ReloadOutlined />} onClick={refresh} disabled={creating} data-testid="qr-refresh">
          {t('login.qr.refresh')}
        </Button>
      </Space>
    </div>
  );
}
