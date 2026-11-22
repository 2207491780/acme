import { useMemo, type ReactElement } from 'react';
import { Empty, Modal, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { FONT_FAMILY, FONT_SIZE, SPACING, TONES } from '@acme/core';
import { diffChangeCount, diffLines } from './designerModel';

/**
 * 版本对比弹层（REQ-20261122-023441 确认③）：一期降级双栏文本 diff 自实现
 * （逐行 LCS，add/del 行染色），左=当前画布（未保存）、右=已保存版本（AC-11）。
 */

const { Text } = Typography;

export interface VersionDiffModalProps {
  open: boolean;
  onClose: () => void;
  leftXml: string;
  leftLabel: string;
  rightXml: string;
  rightLabel: string;
}

const PANEL_MIN_HEIGHT = 420;

function DiffPane({ label, xml, emptyText }: { label: string; xml: string; emptyText: string }): ReactElement {
  const lines = xml === '' ? [] : xml.split('\n');
  return (
    <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
      <Text strong style={{ fontSize: FONT_SIZE.small, marginBottom: SPACING.xs }}>{label}</Text>
      <div
        data-testid="diff-pane"
        style={{
          flex: 1,
          overflow: 'auto',
          border: '1px solid var(--border)',
          borderRadius: 8,
          background: 'var(--surface)',
          minHeight: PANEL_MIN_HEIGHT,
          fontFamily: FONT_FAMILY.mono,
          fontSize: FONT_SIZE.caption,
          lineHeight: 1.6,
          padding: SPACING.sm,
        }}
      >
        {lines.length === 0 ? (
          <Text type="secondary">{emptyText}</Text>
        ) : (
          lines.map((line, index) => (
            <div key={index} style={{ whiteSpace: 'pre-wrap', color: 'var(--text)' }}>
              <span style={{ display: 'inline-block', width: 32, color: 'var(--muted)', userSelect: 'none' }}>{index + 1}</span>
              {line}
            </div>
          ))
        )}
      </div>
    </div>
  );
}

export function VersionDiffModal({ open, onClose, leftXml, leftLabel, rightXml, rightLabel }: VersionDiffModalProps): ReactElement {
  const { t } = useTranslation();
  const rows = useMemo(() => diffLines(leftXml, rightXml), [leftXml, rightXml]);
  const changed = useMemo(() => diffChangeCount(rows), [rows]);
  const leftHtml = useMemo(() => rows.filter((row) => row.op !== 'add').map((row) => (row.op === 'del' ? `- ${row.text}` : `  ${row.text}`)).join('\n'), [rows]);
  const rightHtml = useMemo(() => rows.filter((row) => row.op !== 'del').map((row) => (row.op === 'add' ? `+ ${row.text}` : `  ${row.text}`)).join('\n'), [rows]);

  return (
    <Modal open={open} title={t('workflow.designer.diff.title')} onCancel={onClose} footer={null} width={960} destroyOnClose>
      <Text type="secondary" style={{ display: 'block', marginBottom: SPACING.sm, fontSize: FONT_SIZE.small }}>
        {t('workflow.designer.diff.summary', { count: changed })}
      </Text>
      <div style={{ display: 'flex', gap: SPACING.md }}>
        <DiffPane label={leftLabel} xml={leftHtml} emptyText={t('workflow.designer.diff.placeholder')} />
        <DiffPane
          label={rightLabel}
          xml={rightHtml}
          emptyText={t('workflow.designer.diff.placeholder')}
        />
      </div>
      <Text type="secondary" style={{ fontSize: FONT_SIZE.caption, color: TONES.warning }}>
        {'- = remove / + = add'}
      </Text>
      {leftXml === '' && rightXml === '' ? <Empty description={t('workflow.designer.diff.placeholder')} /> : null}
    </Modal>
  );
}
