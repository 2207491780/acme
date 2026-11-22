import { Button, Empty, Tag, Typography } from 'antd';
import { AimOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { SPACING, FONT_SIZE } from '@acme/core';

/**
 * 校验面板（REQ-20261122-023441 AC-9/AC-10）：静态校验问题与后端 VALIDATION_FAILED 明细
 * 统一逐条渲染（nodeId+message），点击条目回调画布定位标红（X6 highlight 由页面承接）。
 */

const { Text } = Typography;

export interface ValidationItem {
  nodeId: string | null;
  message: string;
}

export type ValidationStatus = 'idle' | 'pass' | 'fail' | 'backend';

export interface ValidationPanelProps {
  status: ValidationStatus;
  items: ValidationItem[];
  /** 点击条目定位画布节点（nodeId 为空的模型级条目不可定位）。 */
  onLocate?: (nodeId: string) => void;
  /** 明细来源标注（core 静态 / 后端 409）。 */
  sourceLabel?: string;
}

export function ValidationPanel({ status, items, onLocate, sourceLabel }: ValidationPanelProps) {
  const { t } = useTranslation();
  return (
    <div data-testid="validation-panel" style={{ padding: 12, borderTop: '1px solid var(--border)', maxHeight: 260, overflowY: 'auto' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: SPACING.sm, marginBottom: SPACING.sm }}>
        <Text strong>{t('workflow.designer.validation.title')}</Text>
        {status === 'backend' && <Tag color="error">{sourceLabel ?? t('workflow.designer.validation.backendTitle')}</Tag>}
        {status === 'pass' && <Tag color="success">{t('workflow.designer.validation.pass')}</Tag>}
        {status === 'fail' && <Tag color="error">{String(items.length)}</Tag>}
      </div>
      {status === 'idle' && (
        <Empty description={t('workflow.designer.validation.empty')} image={Empty.PRESENTED_IMAGE_SIMPLE} />
      )}
      {status === 'pass' && (
        <Text type="success" style={{ fontSize: FONT_SIZE.small }}>{t('workflow.designer.validation.pass')}</Text>
      )}
      {(status === 'fail' || status === 'backend') && (
        <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: SPACING.xs }}>
          {items.map((item, index) => (
            <li
              key={`${item.nodeId ?? 'model'}-${index}`}
              style={{
                display: 'flex',
                alignItems: 'flex-start',
                gap: SPACING.sm,
                padding: '6px 8px',
                borderRadius: 8,
                border: '1px solid var(--border)',
                background: 'var(--surface)',
              }}
            >
              <Text type="danger" style={{ fontSize: FONT_SIZE.small, flex: 1 }}>
                {item.nodeId ? <Text code style={{ fontSize: FONT_SIZE.caption, marginRight: 6 }}>{item.nodeId}</Text> : null}
                {item.message}
              </Text>
              {item.nodeId && onLocate ? (
                <Button
                  size="small"
                  icon={<AimOutlined />}
                  onClick={() => onLocate(item.nodeId as string)}
                  aria-label={t('workflow.designer.validation.locate')}
                >
                  {t('workflow.designer.validation.locate')}
                </Button>
              ) : null}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
