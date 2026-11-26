import { describe, it, expect } from 'vitest';
import { esc, clamp, platformLabel } from '../src';

describe('utils', () => {
  it('should_escape_html_when_rendering_user_content', () => {
    expect(esc('<script>"x"&\'')).toBe('&lt;script&gt;&quot;x&quot;&amp;&#39;');
    expect(esc(null)).toBe('');
  });

  it('should_clamp_progress_when_out_of_range', () => {
    expect(clamp(120, 0, 100)).toBe(100);
    expect(clamp(-5, 0, 100)).toBe(0);
    expect(clamp(42, 0, 100)).toBe(42);
  });

  it('should_label_platform_from_shared_i18n_when_displaying', () => {
    expect(platformLabel('desktop')).toBe('PC 桌面端');
  });
});