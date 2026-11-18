// 路由常量源（契约①⑨：既有 TabKey 语义保留）与深链映射（契约⑦：acme:// 四 Tab）。
// 本文件对 react-navigation 只做 type-only 引用（运行时零依赖），
// 使测试可在无 navigator mock 的情况下直接断言路由常量与映射表。
import type { LinkingOptions, NavigatorScreenParams } from '@react-navigation/native';

/** 主 Tabs 路由名 = 既有 TabKey（语义/顺序与旧手动切换完全一致）。 */
export type TabKey = 'dashboard' | 'releases' | 'apps' | 'settings';

export const TAB_KEYS: readonly TabKey[] = ['dashboard', 'releases', 'apps', 'settings'];

/** Stack 层路由：登录屏为 Stack 首屏（initialRouteName），Main 承载主 Tabs。 */
export const LOGIN_ROUTE = 'Login' as const;
export const MAIN_ROUTE = 'Main' as const;

// type 别名（非 interface）：react-navigation v7 的 ParamListBase 约束要求可赋值给
// { [key: string]: object | undefined }，type 字面量才具备隐式索引签名兼容。
// Main 挂 NavigatorScreenParams：linking 嵌套 screens（四 Tab 深链）与 navigate('Main', {screen}) 获得类型。
export type RootStackParamList = {
  Login: undefined;
  Main: NavigatorScreenParams<MainTabParamList> | undefined;
};

export type MainTabParamList = Record<TabKey, undefined>;

/** 深链 prefix（契约⑦）。 */
export const LINK_PREFIXES: readonly string[] = ['acme://'];

/** 路径映射表：TabKey → 深链 path（当前路径名 = 路由名；单一事实源 TAB_KEYS 派生，杜绝漂移）。 */
export const TAB_PATHS: Readonly<Record<TabKey, string>> = TAB_KEYS.reduce(
  (acc, key) => {
    acc[key] = key;
    return acc;
  },
  {} as Record<TabKey, string>,
);

/**
 * 纯函数：深链路径 → TabKey。非法/未知路径回 dashboard（AC-2）。
 * 兼容可选的前导斜杠与 query/hash 尾巴：'releases'、'/settings'、'apps?x=1' 均可解析。
 */
export function resolveDeepLinkPath(path: string): TabKey {
  const segment = (path ?? '').replace(/^\/+/, '').split(/[/?#]/)[0] ?? '';
  return (TAB_KEYS as readonly string[]).includes(segment) ? (segment as TabKey) : 'dashboard';
}

/** NavigationContainer 深链配置：acme://releases → Main Tabs 的 releases 屏（冷启动与运行态同径）。 */
export const linking: LinkingOptions<RootStackParamList> = {
  prefixes: [...LINK_PREFIXES],
  config: {
    screens: {
      [LOGIN_ROUTE]: 'login',
      [MAIN_ROUTE]: {
        screens: TAB_PATHS,
      },
    },
  },
};
