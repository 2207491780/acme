import type { ReactElement } from 'react';
import { useTranslation } from 'react-i18next';
import { useStore } from '../../store/useStore';
import { useSystemPrefersDark } from '../../hooks/useSystemPrefersDark';
import { PALETTE_ITEMS } from './designerModel';
import type { BpmnElementType } from '@acme/core';
import { resolveThemeMode, resolveCanvasPalette, SPACING, FONT_SIZE } from '@acme/core';

/**
 * 元素面板（REQ-20261122-023441 AC-3）：一期白名单项左列展示，HTML5 原生拖拽
 * （dataTransfer 传元素类型，画布 onDrop 建节点）；白名单外元素不出现（契约 §2）。
 * 色值全部走 core 令牌（确认⑦：resolveCanvasPalette 随主题解析）。
 */

export const BPMN_DND_MIME = 'text/acme-bpmn-element';

/** 一期白名单元素的小图标（纯内联 SVG，path 从 core 不取——形状语义而非配色；色值走令牌）。 */
function PaletteGlyph({ type, color }: { type: BpmnElementType; color: string }): ReactElement {
  const common = { fill: 'none', stroke: color, strokeWidth: 1.6 } as const;
  switch (type) {
    case 'StartEvent':
      return <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden><circle cx="8" cy="8" r="6" {...common} /></svg>;
    case 'EndEvent':
      return <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden><circle cx="8" cy="8" r="6" fill={color} /></svg>;
    case 'XOR':
      return <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden><path d="M8 1 15 8 8 15 1 8Z" {...common} /><path d="M5 5l6 6M11 5l-6 6" {...common} /></svg>;
    case 'AND':
      return <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden><path d="M8 1 15 8 8 15 1 8Z" {...common} /><path d="M8 5v6M5 8h6" {...common} /></svg>;
    case 'UserTask':
      return <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden><rect x="1" y="4" width="14" height="8" rx="2" {...common} /><circle cx="4.5" cy="8" r="1.2" fill={color} /></svg>;
    case 'ServiceTask':
      return <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden><rect x="1" y="4" width="14" height="8" rx="2" {...common} /><path d="M4 8h2l1-2 1.6 4L10 8h2" {...common} /></svg>;
    case 'EmbeddedSubProcess':
      return <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden><rect x="1" y="3" width="14" height="10" rx="2" {...common} /><rect x="3.5" y="6" width="9" height="4" rx="1" {...common} /></svg>;
    case 'BoundaryEvent':
      return <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden><circle cx="8" cy="8" r="6" {...common} strokeDasharray="2 1.6" /><path d="M8 5v3l2 2" {...common} /></svg>;
    default:
      return <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden><rect x="2" y="2" width="12" height="12" rx="2" {...common} /></svg>;
  }
}

export function Palette(): ReactElement {
  const { t } = useTranslation();
  const preferences = useStore((s) => s.state.settings.preferences);
  const systemPrefersDark = useSystemPrefersDark();
  const resolvedMode = resolveThemeMode(preferences.mode, systemPrefersDark);
  const canvas = resolveCanvasPalette(resolvedMode);

  return (
    <aside
      data-testid="bpmn-palette"
      style={{
        width: 168,
        flexShrink: 0,
        borderRight: '1px solid var(--border)',
        background: 'var(--surface)',
        padding: SPACING.md,
        overflowY: 'auto',
      }}
    >
      <div style={{ fontSize: FONT_SIZE.small, fontWeight: 600, color: 'var(--text)' }}>{t('workflow.designer.palette.title')}</div>
      <div style={{ fontSize: FONT_SIZE.caption, color: 'var(--muted)', marginTop: 2, marginBottom: SPACING.md }}>
        {t('workflow.designer.palette.hint')}
      </div>
      <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: SPACING.xs }}>
        {PALETTE_ITEMS.map((type) => (
          <li
            key={type}
            data-testid={`palette-item-${type}`}
            draggable
            onDragStart={(event) => {
              event.dataTransfer.setData(BPMN_DND_MIME, type);
              event.dataTransfer.effectAllowed = 'copy';
            }}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: SPACING.sm,
              padding: '6px 8px',
              borderRadius: 8,
              border: '1px solid var(--border)',
              background: 'var(--surface)',
              fontSize: FONT_SIZE.small,
              color: 'var(--text)',
              cursor: 'grab',
              userSelect: 'none',
            }}
          >
            <PaletteGlyph type={type} color={canvas.nodeText2} />
            <span>{t(`workflow.designer.palette.items.${type}`)}</span>
          </li>
        ))}
      </ul>
    </aside>
  );
}
