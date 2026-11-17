import { useId, useState, type ReactNode } from 'react';
import { Button, Card, Space, Tag, Typography } from 'antd';
import { DownOutlined, FilterOutlined, UpOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { SPACING } from '@acme/core';

interface FilterPanelProps {
  children: ReactNode;
  advanced?: ReactNode;
  advancedActive?: boolean;
  actions?: ReactNode;
}

/** 统一筛选字段：小标签 + 全宽控件，落入 FilterPanel 等宽自适应网格；wide 跨两列（时间范围等宽控件）。 */
export function FilterField({ label, htmlFor, wide = false, children }: {
  label: ReactNode;
  htmlFor?: string;
  wide?: boolean;
  children: ReactNode;
}) {
  return (
    <div className={wide ? 'filter-field filter-field-wide' : 'filter-field'}>
      <label className="filter-field-label" htmlFor={htmlFor}>{label}</label>
      {children}
    </div>
  );
}

export function FilterPanel({ children, advanced, advancedActive = false, actions }: FilterPanelProps) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);
  const advancedId = useId();

  return (
    <Card
      className="filter-panel"
      role="region"
      aria-label={t('common.filters.region')}
      style={{ marginBottom: SPACING.lg }}
    >
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: SPACING.sm, marginBottom: SPACING.md }}>
        <Space size={SPACING.sm} wrap>
          <FilterOutlined aria-hidden />
          <Typography.Text strong>{t(expanded ? 'common.filters.advanced' : 'common.filters.basic')}</Typography.Text>
          {advanced && advancedActive ? <Tag color="processing">{t('common.filters.active')}</Tag> : null}
        </Space>
        {advanced ? (
          <Button
            type="text"
            icon={expanded ? <UpOutlined aria-hidden /> : <DownOutlined aria-hidden />}
            aria-expanded={expanded}
            aria-controls={advancedId}
            onClick={() => setExpanded((value) => !value)}
          >
            {t(expanded ? 'common.filters.collapse' : 'common.filters.expand')}
          </Button>
        ) : null}
      </div>
      <div className="filter-panel-fields">
        {children}
      </div>
      {advanced ? (
        <div id={advancedId} hidden={!expanded} style={{ marginTop: SPACING.md, paddingTop: SPACING.md, borderTop: '1px solid var(--border)' }}>
          <div className="filter-panel-fields">
            {advanced}
          </div>
        </div>
      ) : null}
      {actions ? <div className="filter-panel-actions">{actions}</div> : null}
    </Card>
  );
}