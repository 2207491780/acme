import { Button, Tag, Typography } from 'antd';
import { AimOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { SPACING, FONT_SIZE, RADII } from '@acme/core';

/**
 * 校验面板（REQ-20261122-023441 AC-9/AC-10；REQ-20261124-224523 AC-7 收口）：
 * 静态校验问题与后端 VALIDATION_FAILED 明细统一逐条渲染（nodeId+message），点击条目回调画布定位标红。
 * idle/pass=一行紧凑状态条（Empty 大块占位移除，画布保有主视区高度）；fail/backend=整面板 maxHeight 内滚
 * 有上限（列表超限时面板滚动，状态条与明细同域滚动）。
 */

const { Text } = Typography;

/** 面板内滚上限（AC-7：有上限，画布主视区不被长列表挤压）。 */
const PANEL_MAX_HEIGHT = 160;

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
    <div
      data-testid="validation-panel"
      style={{
        padding: `${SPACING.xs}px ${SPACING.md}px`,
        borderTop: '1px solid var(--border)',
        maxHeight: PANEL_MAX_HEIGHT,
        overflowY: 'auto',
        boxSizing: 'border-box',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: SPACING.sm, minHeight: 28 }}>
        <Text strong style={{ fontSize: FONT_SIZE.small }}>{t('workflow.designer.validation.title')}</Text>
        {status === 'idle' && (
          <Text type="secondary" style={{ fontSize: FONT_SIZE.small }}>{t('workflow.designer.validation.empty')}</Text>
        )}
        {status === 'pass' && (
          <Text type="success" style={{ fontSize: FONT_SIZE.small }}>{t('workflow.designer.validation.pass')}</Text>
        )}
        {status === 'fail' && <Tag color="error">{String(items.length)}</Tag>}
        {status === 'backend' && <Tag color="error">{sourceLabel ?? t('workflow.designer.validation.backendTitle')}</Tag>}
      </div>
      {(status === 'fail' || status === 'backend') && (
        <ul
          style={{
            listStyle: 'none',
            margin: 0,
            marginTop: SPACING.xs,
            padding: 0,
            display: 'flex',
            flexDirection: 'column',
            gap: SPACING.xs,
          }}
        >
          {items.map((item, index) => (
            <li
              key={`${item.nodeId ?? 'model'}-${index}`}
              style={{
                display: 'flex',
                alignItems: 'flex-start',
                gap: SPACING.sm,
                padding: '6px 8px',
                borderRadius: RADII.sm,
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
