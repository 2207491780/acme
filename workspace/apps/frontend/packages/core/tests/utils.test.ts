import { describe, it, expect } from 'vitest';
import { esc, isVersion, compareVersion, clamp, statusLabel, platformLabel } from '../src';

describe('utils', () => {
  it('should_escape_html_when_rendering_user_content', () => {
    expect(esc('<script>"x"&\'')).toBe('&lt;script&gt;&quot;x&quot;&amp;&#39;');
    expect(esc(null)).toBe('');
  });

  it('should_validate_semver_when_checking_version', () => {
    expect(isVersion('1.2.3')).toBe(true);
    expect(isVersion('1.2.3-beta.1')).toBe(true);
    expect(isVersion('1.2')).toBe(false);
    expect(isVersion('abc')).toBe(false);
  });

  it('should_compare_versions_when_sorting', () => {
    expect(compareVersion('1.10.0', '1.9.0')).toBeGreaterThan(0);
    expect(compareVersion('2.0.0', '1.99.99')).toBeGreaterThan(0);
    expect(compareVersion('1.2.3', '1.2.3')).toBe(0);
  });

  it('should_clamp_progress_when_out_of_range', () => {
    expect(clamp(120, 0, 100)).toBe(100);
    expect(clamp(-5, 0, 100)).toBe(0);
    expect(clamp(42, 0, 100)).toBe(42);
  });

  it('should_label_status_and_platform_when_displaying', () => {
    expect(statusLabel('published')).toBe('已发布');
    expect(platformLabel('desktop')).toBe('PC 桌面端');
  });
});