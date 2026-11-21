// App 壳（REQ-20261119-032108 重构）：react-navigation v7 接入。
// 层级：ThemeProvider（preferences.mode 三态解析 → resolvePalette）→ ToastProvider（顶层 toast）→
// SafeAreaView[StatusBar 联动 + 离线横幅 + 品牌头] → ErrorBoundary（外层防线）→
// NavigationContainer(linking) → Stack[Login(initial) → Main(Tabs: 4 Tab，各含内层 ErrorBoundary)]。
// 登录语义（契约⑨）：AuthNavigator 统一联动 session——建立 → replace 主 Tabs；登出 → reset 回 Login；
// 深链（契约⑦⑧）：linking prefixes acme:// + 四 Tab 路径映射（nav.ts 单一事实源）。
import { useEffect, useMemo, useState } from 'react';
import type { ComponentType } from 'react';
import {
  SafeAreaView,
  StatusBar,
  View,
  Text,
  Pressable,
  Platform,
  StyleSheet,
} from 'react-native';
import { ACTIVE_STATUSES } from '@acme/core';
import { NavigationContainer, useNavigation } from '@react-navigation/native';
import type { BottomTabBarProps } from '@react-navigation/bottom-tabs';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { useStore } from './src/store/useStore';
import { useAuthStore } from './src/store/authStore';
import { useBSessionStore } from './src/store/bSessionStore';
import { useMeStore } from './src/store/meStore';
import { startAuthRenewal } from './src/api/client';
import { registerPushTokenAfterLogin } from './src/utils/push';
import { initMobileStorage } from './src/utils/storage';
import { hydrateDicts } from './src/use-dict';
import { useAccent, useOnAccent } from './src/hooks';
import { useNetworkStatus } from './src/hooks/useNetworkStatus';
import { usePreferencesSync } from './src/hooks/usePreferencesSync';
import { ThemeProvider, useAppTheme, barStyleFor } from './src/theme';
import { i18n, useTranslation } from './src/i18n';
import {
  LOGIN_ROUTE,
  MAIN_ROUTE,
  QR_CONFIRM_ROUTE,
  TAB_KEYS,
  linking,
  type MainTabParamList,
  type RootStackParamList,
  type TabKey,
} from './src/nav';
import { DashboardScreen } from './src/screens/DashboardScreen';
import { ReleasesScreen } from './src/screens/ReleasesScreen';
import { AppsScreen } from './src/screens/AppsScreen';
import { SettingsScreen } from './src/screens/SettingsScreen';
import { LoginScreen } from './src/screens/LoginScreen';
import { QrConfirmScreen } from './src/screens/QrConfirmScreen';
import { ErrorBoundary } from './src/components/ErrorBoundary';
import { ToastProvider, toast } from './src/components/ui';
import type { NeutralPalette } from '@acme/core';
import { BrandMark, IconDashboard, IconReleases, IconApps, IconSettings } from './src/components/icons';

// 主 Tabs 元数据：label 走 i18n 键（nav.mobile.tabs.*，契约③），顺序 = TAB_KEYS。
const TAB_META: Record<TabKey, { labelKey: string; Icon: ComponentType<{ color: string; size?: number }> }> = {
  dashboard: { labelKey: 'nav.mobile.tabs.dashboard', Icon: IconDashboard },
  releases: { labelKey: 'nav.mobile.tabs.releases', Icon: IconReleases },
  apps: { labelKey: 'nav.mobile.tabs.apps', Icon: IconApps },
  settings: { labelKey: 'nav.mobile.tabs.settings', Icon: IconSettings },
};

const Tabs = createBottomTabNavigator<MainTabParamList>();
const Stack = createNativeStackNavigator<RootStackParamList>();

/** 每 Tab 内层 ErrorBoundary（契约④第二道防线）：单 Tab 异常兜底可重试，不影响其他 Tab。 */
function withTabBoundary(Screen: ComponentType): ComponentType {
  return function BoundedTabScreen() {
    return (
      <ErrorBoundary>
        <Screen />
      </ErrorBoundary>
    );
  };
}

const TAB_SCREENS: Record<TabKey, ComponentType> = {
  dashboard: withTabBoundary(DashboardScreen),
  releases: withTabBoundary(ReleasesScreen),
  apps: withTabBoundary(AppsScreen),
  settings: withTabBoundary(SettingsScreen),
};

/** 自绘 TabBar（保持既有设计语言：accent pill + 活跃数 badge；路由状态来自 bottom-tabs）。 */
function AppTabBar({ state, navigation }: BottomTabBarProps) {
  const accent = useAccent();
  const onAccentHex = useOnAccent();
  const { palette } = useAppTheme();
  const { t } = useTranslation();
  const activeCount = useStore(
    (s) => s.state.releases.filter((r) => ACTIVE_STATUSES.includes(r.status)).length,
  );
  const styles = useMemo(() => makeStyles(palette), [palette]);
  return (
    <View style={styles.tabbar}>
      {state.routes.map((route, index) => {
        const key = route.name as TabKey;
        const meta = TAB_META[key];
        const active = state.index === index;
        const color = active ? accent : palette.muted;
        return (
          <Pressable
            key={key}
            accessibilityRole="tab"
            accessibilityState={{ selected: active }}
            onPress={() => navigation.navigate(route.name)}
            style={styles.tab}
          >
            <View style={[styles.tabIconWrap, active ? { backgroundColor: `${accent}1f` } : null]}>
              <meta.Icon color={color} size={22} />
              {key === 'releases' && activeCount > 0 ? (
                <View style={[styles.badge, { backgroundColor: accent }]}>
                  <Text style={[styles.badgeText, { color: onAccentHex }]}>{activeCount}</Text>
                </View>
              ) : null}
            </View>
            <Text style={[styles.tabLabel, { color }]}>{t(meta.labelKey)}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

function MainTabs() {
  return (
    <Tabs.Navigator screenOptions={{ headerShown: false }} tabBar={(props) => <AppTabBar {...props} />}>
      {TAB_KEYS.map((key) => (
        <Tabs.Screen key={key} name={key} component={TAB_SCREENS[key]} />
      ))}
    </Tabs.Navigator>
  );
}

/**
 * 登录态 → 导航联动（契约①⑨）。导出供测试（AC-1）：
 * - session 建立（含冷启动已有会话）：当前在 Login 屏 → navigation.replace 主 Tabs；
 * - 登出：reset 回 Login（Stack 首屏语义保留）；
 * - 深链冷启动已落在 Main/*：不干预（linking 优先）。
 */
export function AuthNavigator() {
  const isAuthed = useAuthStore((s) => s.session != null);
  const navigation = useNavigation<NativeStackNavigationProp<RootStackParamList>>();
  useEffect(() => {
    const state = navigation.getState();
    const current = state?.routes[state.index]?.name;
    const atLogin = !current || current === LOGIN_ROUTE;
    if (isAuthed && atLogin) {
      navigation.replace(MAIN_ROUTE);
    } else if (!isAuthed && !atLogin) {
      navigation.reset({ index: 0, routes: [{ name: LOGIN_ROUTE }] });
    }
  }, [isAuthed, navigation]);
  return null;
}

function AppShell() {
  const { palette, theme } = useAppTheme();
  const accent = useAccent();
  const { t } = useTranslation();
  const styles = useMemo(() => makeStyles(palette), [palette]);
  const dispatch = useStore((s) => s.dispatch);
  const motion = useStore((s) => s.state.settings.preferences.motionEnabled);
  const offline = useNetworkStatus();
  // 登录态（login-app）：启动恢复安全存储会话 + 续约守护循环；未登录渲染登录 Stack
  const session = useAuthStore((s) => s.session);
  const hydrated = useAuthStore((s) => s.hydrated);
  const hydrate = useAuthStore((s) => s.hydrate);
  // 业务态持久化（REQ-20261114-120110 AC-3）：AsyncStorage 异步灌入同步镜像后重载
  // store（loadState/loadQuery 经 parse-state/isQuery 运行时校验），完成前不渲染主界面
  const [businessReady, setBusinessReady] = useState(false);
  const isAuthed = session != null;
  const barStyle = barStyleFor(theme);
  // 偏好后端持久化（REQ-20261121-200415 契约⑨）：登录拉取后端权威覆盖本地（AsyncStorage
  // STORAGE_KEY 单 blob 兜底）+ 偏好变更防抖 800ms PUT themePreference + 未登录/失败静默。
  usePreferencesSync();

  useEffect(() => {
    void hydrate();
    // B 端员工会话（REQ-20261119-040838）：独立键恢复，仅驱动 Login 扫码确认入口（不进主 Tabs）
    void useBSessionStore.getState().hydrate();
    const stopRenewal = startAuthRenewal();
    // 业务态持久化（REQ-20261114-120110 AC-3/AMB-5）：AsyncStorage 灌入同步镜像后重载
    // store（loadState/loadQuery 经 parse-state 运行时校验），字典缓存同步灌回；
    // 就绪前不渲染主界面
    void initMobileStorage().then(() => {
      hydrateDicts();
      useStore.getState().reloadState();
      setBusinessReady(true);
    });
    return stopRenewal;
  }, [hydrate]);

  // 画像加载（AC-2，与 web 同构）：登录态建立即拉取服务端画像（401 由 client 续约/清会话，
  // 403 由 meStore 清会话回登录屏）。依赖收敛为 isAuthed：滑动续约会整体替换 session 对象，
  // 以 session 为依赖会导致每次续约重复拉取 me。
  useEffect(() => {
    if (isAuthed) {
      void useMeStore.getState().load();
    } else {
      useMeStore.getState().clear();
    }
  }, [isAuthed]);

  useEffect(() => {
    if (!motion) return;
    const timer = setInterval(() => dispatch({ type: 'TICK' }), 1200);
    return () => clearInterval(timer);
  }, [dispatch, motion]);

  // 推送令牌注册（REQ-20261119-082531 契约⑧，AC-8）：员工 B 会话建立（登录/冷启动恢复）后注册——
  // EXPO_PUBLIC_PROJECT_ID 缺省时降级跳过（不触系统模块、零外呼）并告知用户；同 token upsert 幂等。
  const bSession = useBSessionStore((s) => s.session);
  useEffect(() => {
    if (!bSession) {
      return;
    }
    void registerPushTokenAfterLogin().then((outcome) => {
      if (outcome === 'registered') {
        toast(i18n.t('profile.mobile.pushRegistered') as string, 'success');
      } else if (outcome === 'skipped-no-project') {
        toast(i18n.t('profile.mobile.pushSkipNoProject') as string, 'info');
      }
    });
  }, [bSession]);

  if (!hydrated || !businessReady) {
    return (
      <SafeAreaView style={styles.root}>
        <StatusBar barStyle={barStyle} backgroundColor={palette.bg} />
        <View style={[styles.root, styles.center]}>
          <Text style={styles.mutedText}>{t('nav.mobile.sessionRestoring')}</Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.root}>
      <StatusBar barStyle={barStyle} backgroundColor={palette.bg} />
      {offline ? (
        <View style={styles.offlineBanner} accessibilityRole="alert">
          <Text style={styles.offlineText}>{t('nav.mobile.offline')}</Text>
        </View>
      ) : null}
      <View style={styles.header}>
        <BrandMark color={accent} />
        <View style={{ flex: 1 }}>
          <Text style={styles.brand}>{isAuthed ? t('nav.brand.title') : t('login.brand.title')}</Text>
          <Text style={styles.brandSub}>{isAuthed ? t('nav.mobile.appSub') : t('nav.mobile.loginSub')}</Text>
        </View>
        {isAuthed ? (
          <View style={[styles.pill, { borderColor: `${accent}59`, backgroundColor: `${accent}1f` }]}>
            <Text style={[styles.pillText, { color: accent }]}>{t('nav.header.runtimeMobile')}</Text>
          </View>
        ) : null}
      </View>

      <View style={{ flex: 1 }}>
        <ErrorBoundary>
          <NavigationContainer linking={linking}>
            <Stack.Navigator initialRouteName={LOGIN_ROUTE} screenOptions={{ headerShown: false }}>
              <Stack.Screen name={LOGIN_ROUTE} component={LoginScreen} />
              <Stack.Screen name={MAIN_ROUTE} component={MainTabs} />
              <Stack.Screen name={QR_CONFIRM_ROUTE} component={QrConfirmScreen} />
            </Stack.Navigator>
            <AuthNavigator />
          </NavigationContainer>
        </ErrorBoundary>
      </View>
    </SafeAreaView>
  );
}

export default function App() {
  return (
    <ThemeProvider>
      <ToastProvider>
        <AppShell />
      </ToastProvider>
    </ThemeProvider>
  );
}

const makeStyles = (p: NeutralPalette) =>
  StyleSheet.create({
    root: {
      flex: 1,
      backgroundColor: p.bg,
      paddingTop: Platform.OS === 'android' ? StatusBar.currentHeight : 0,
    },
    center: { alignItems: 'center', justifyContent: 'center' },
    mutedText: { color: p.muted, fontSize: 13 },
    offlineBanner: {
      backgroundColor: p.elevated,
      borderBottomWidth: 1,
      borderBottomColor: p.border,
      paddingVertical: 7,
      alignItems: 'center',
    },
    offlineText: { color: p.muted, fontSize: 12, fontWeight: '600' },
    header: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 12,
      paddingHorizontal: 16,
      paddingVertical: 12,
      borderBottomWidth: 1,
      borderBottomColor: p.border,
    },
    brand: { color: p.text, fontSize: 18, fontWeight: '800' },
    brandSub: { color: p.muted, fontSize: 10, letterSpacing: 2, marginTop: 2 },
    pill: { borderWidth: 1, borderRadius: 999, paddingHorizontal: 10, paddingVertical: 4 },
    pillText: { fontSize: 11, fontWeight: '700' },
    tabbar: {
      flexDirection: 'row',
      borderTopWidth: 1,
      borderTopColor: p.border,
      backgroundColor: p.surface,
      paddingVertical: 8,
      paddingBottom: 10,
    },
    tab: { flex: 1, alignItems: 'center', gap: 4 },
    tabIconWrap: { width: 46, height: 30, borderRadius: 10, alignItems: 'center', justifyContent: 'center' },
    badge: {
      position: 'absolute',
      top: -4,
      right: -2,
      minWidth: 16,
      height: 16,
      borderRadius: 99,
      paddingHorizontal: 4,
      alignItems: 'center',
      justifyContent: 'center',
    },
    badgeText: { fontSize: 10, fontWeight: '800' },
    tabLabel: { fontSize: 11, fontWeight: '600' },
  });
