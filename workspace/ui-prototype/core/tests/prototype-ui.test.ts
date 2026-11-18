import { describe, it, expect } from 'vitest';
import { cascaderOptions, collectOrgKeys, MEMBERS, ORG_LEAF_MEMBERS, ORG_TREE, seedState, TRANSFER_POOL } from '../src';

const now = Date.parse('2026-11-11T10:00:00Z');
const state = seedState(now);

describe('prototype ui gallery data', () => {
  it('should_build_org_tree_with_unique_keys_and_registered_members', () => {
    const keys = collectOrgKeys(ORG_TREE);
    expect(new Set(keys).size).toBe(keys.length);
    expect(keys.filter((k) => k.startsWith('u-'))).toHaveLength(6);
    // 叶子成员必须是 MEMBERS 在册人员
    for (const name of ORG_LEAF_MEMBERS) {
      expect(MEMBERS.some((m) => m.name === name)).toBe(true);
    }
  });

  it('should_offer_transfer_pool_grouped_by_app', () => {
    expect(TRANSFER_POOL.length).toBeGreaterThanOrEqual(6);
    for (const item of TRANSFER_POOL) {
      expect(item.label.length).toBeGreaterThan(0);
      expect(item.group.length).toBeGreaterThan(0);
    }
  });

  it('should_build_cascader_env_app_version_options_from_store', () => {
    const options = cascaderOptions(state);
    expect(options.map((o) => o.value)).toEqual(['dev', 'test', 'prod']);
    for (const envNode of options) {
      expect(envNode.children).toHaveLength(state.apps.length);
      for (const appNode of envNode.children!) {
        // 每个应用节点的叶子版本都来自该应用在该环境的真实发布
        for (const leaf of appNode.children ?? []) {
          expect(
            state.releases.some(
              (r) => r.appId === appNode.value && r.env === envNode.value && `v${r.version}` === leaf.label,
            ),
          ).toBe(true);
        }
      }
    }
  });

  it('should_return_deterministic_cascader_options', () => {
    expect(cascaderOptions(state)).toEqual(cascaderOptions(state));
  });
});
