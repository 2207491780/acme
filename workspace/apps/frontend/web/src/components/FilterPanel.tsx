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
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: SPACING.sm, marginBottom: SPACING.lg }}>
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
      <div className="filter-panel-fields" style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'end', gap: SPACING.lg }}>
        {children}
      </div>
      {advanced ? (
        <div id={advancedId} hidden={!expanded} style={{ marginTop: SPACING.lg, paddingTop: SPACING.lg, borderTop: '1px solid var(--border)' }}>
          <div className="filter-panel-fields" style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'end', gap: SPACING.lg }}>
            {advanced}
          </div>
        </div>
      ) : null}
      {actions ? <div style={{ marginTop: SPACING.lg }}>{actions}</div> : null}
    </Card>
  );
}
