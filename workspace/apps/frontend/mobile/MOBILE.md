# mobile — 移动端

React Native + Expo 移动应用，复用共享 `@acme/core` 业务层，实现与 Web 同构的「ACME 多端前端交付台」。UI 为 **Ant Design 风格的 RN 原生组件**（非 `antd-mobile`——后者基于 DOM，与 RN 原生渲染不兼容），共享 Zustand store 模式与同一套发布单业务逻辑。

## 技术栈

Expo SDK 52（expo ~52.0.49 / react 18.3.1 / react-native 0.76.9）+ Zustand 5 + TypeScript 5；Vitest 单测；Metro  monorepo 配置直接复用 `@acme/core` 的 TS 源码。`expo-asset` / `expo-font` 为 Expo SDK 内置基线包，显式声明以便 pnpm 严格隔离下 Metro `getDefaultConfig` 可解析。

REQ-20261119-032108 起新增脚手架依赖（已登记 `.ai/rules/project.md`，依赖事实源见该文件）：
**react-navigation v7**（`@react-navigation/native` + `bottom-tabs` + `native-stack`，Tab 路由/Stack/深链）、
`react-native-screens ~4.4`、`react-native-safe-area-context ~4.12`（v7 peer）、
`@react-native-community/netinfo ^11`（网络状态）。i18n 不装包：直接消费 `@acme/core` 内联初始化的
i18next 实例与 `localeStore`。eas-cli 不进依赖，构建脚本经 `npx eas-cli` 调用。

## 目录结构

```text
mobile/
├── MOBILE.md
├── app.json                # Expo 配置（双主题 automatic、scheme acme、icon/splash/adaptiveIcon、
│                           #   android intentFilters autoVerify、newArchEnabled）
├── eas.json                # EAS 构建（development 模拟器 / preview 内测 apk / production aab）
├── assets/                 # icon.png / adaptive-icon.png / splash.png（脚本生成占位，见下）
├── index.ts                # registerRootComponent(App)
├── App.tsx                 # ThemeProvider → ToastProvider → SafeAreaView[StatusBar/离线横幅/品牌头]
│                           #   → ErrorBoundary → NavigationContainer(linking) → Stack[Login→Main(Tabs)]
├── package.json            # @acme/mobile
├── babel.config.js         # babel-preset-expo
├── metro.config.js         # monorepo：watchFolders / nodeModulesPaths / @acme/core→src
├── tsconfig.json           # extends expo/tsconfig.base，@acme/core paths 别名
├── vitest.config.ts
├── src/
│   ├── nav.ts              # TabKey/路由常量（Login/Main + 四 Tab）/ 深链映射（linking，type-only 引 RN）
│   ├── theme.ts            # 双主题：ThemeProvider/useAppTheme 消费 core settings.theme → resolvePalette
│   │                       #   （dark→DARK_NEUTRALS/light→LIGHT_NEUTRALS）；静态 palette 导出保留 dark 缺省
│   ├── i18n.ts             # useTranslation：消费 core i18next 实例 + localeStore（mobile 不装 i18n 包）
│   ├── hooks.ts            # useAccent / useOnAccent
│   ├── hooks/useNetworkStatus.ts  # NetInfo 订阅 + 启动探测 → offline 布尔（离线横幅数据源）
│   ├── store/useStore.ts   # Zustand 包裹 core reducer（与 Web 同构）+ 筛选态持久化
│   ├── store/meStore.ts    # 服务端画像镜像 store（语义对齐 core meStore；AMB-2/4 裁决）
│   ├── store/authStore.ts  # 登录会话（双令牌安全存储；session 驱动导航联动）
│   ├── utils/storage.ts    # 业务态 AsyncStorage 持久化（同步镜像 + 写穿透 + initMobileStorage 冷启动 hydrate）
│   ├── components/         # ui.tsx（Card/Tag/Button/ProgressBar/Loading/Empty/ToastProvider+toast）/
│   │                       #   ErrorBoundary（class 兜底 + 重试重挂载）/
│   │                       #   PermissionGate（screen 级权限门禁）/ icons.tsx（纯 View 矢量图标）
│   ├── use-dict.ts         # 字典消费 hook（core useDict 镜像 + AsyncStorage 缓存持久化；AMB-4 裁决）
│   └── screens/            # Dashboard / Releases / Apps / Settings / Login（全量 t() + 双主题派生）
└── tests/                  # Vitest：store/theme/导航联动/深链/边界/网络/Toast 等（10 文件 66 用例）
```

## 导航（react-navigation v7）

- **Stack**：`Login`（initialRouteName，登录屏）→ `Main`（承载主 Tabs）；`headerShown: false`，品牌头由 App 壳统一渲染。
- **Main Tabs**：bottom-tabs 四 Tab（dashboard/releases/apps/settings），自定义 `AppTabBar` 保持既有设计
  （accent pill + 发布活跃数 badge）；每 Tab 组件外再包一层 `ErrorBoundary`（两道防线，单 Tab 崩溃可重试且不波及其他 Tab）。
- **登录联动**：`AuthNavigator`（容器内）消费 `authStore.session`——建立会话（含冷启动已有会话）
  `navigation.replace('Main')`；登出 `navigation.reset` 回 `Login`；深链冷启动已落 Main/* 时不干预。
- **TabKey 语义保留**：`nav.ts` 是路由常量唯一事实源（`TAB_KEYS` 顺序 = 旧手动切换顺序），Tab 路由名 = TabKey。

## 主题（双主题）

- `src/theme.ts` 导出 `ThemeProvider` / `useAppTheme()`（别名 `useThemeTheme`）：消费 core `settings.theme`，
  经 `resolvePalette` 派生完整 NeutralPalette（dark→`DARK_NEUTRALS` / light→`LIGHT_NEUTRALS`，引用同一性由
  `tests/theme.test.ts` 锁定）；`barStyleFor(theme)` 联动 StatusBar。
- 样式模式：模块级 `makeStyles(palette)` 工厂 + 组件内 `useMemo`（palette 引用随 resolvePalette 稳定）；
  全部组件样式随主题即时刷新，静态 `palette` 导出仅为历史兼容（dark 缺省），组件不得新增使用。
- `app.json` `userInterfaceStyle: automatic`（跟随系统，用户可在设置页覆盖 dark/light）。

## i18n

- 复用 core 资源域（nav/login/dashboard/releases/apps/settings/common），mobile 缺键以各域内 `mobile` 组
  成对新增（zh-CN/en-US 形状一致性由 scaffold 测试断言）；`useTranslation()` 来自 `src/i18n.ts`
  （core i18next 单例 + localeStore 订阅），语言切换在设置页「界面语言」分段控件（zh-CN / en-US）。
- screens/App 硬编码中文已全量迁移 t() 键；core 标签函数（statusLabel/HEALTH_LABELS 等）仍为数据驱动，
  语义标签部分（状态/平台/环境）已改走 `releases.*` 资源键。

## 深链

- `linking`（`src/nav.ts`）：prefixes `['acme://']`；`acme://login` → Login，`acme://dashboard|releases|apps|settings`
  → Main 对应 Tab；冷启动与运行态同径；非法路径回落 dashboard（`resolveDeepLinkPath` 纯函数，测试锁定）。
- `app.json`：android `intentFilters`（action VIEW + autoVerify + scheme acme）；iOS scheme 由 expo
  `scheme: "acme"` 生成 URL type。

## 构建与资源（EAS）

- `assets/` 由一次性脚本生成（`.ai-tmp/gen-mobile-assets.mjs`，纯 node:zlib 手写 PNG，无 canvas 依赖）：
  `icon.png` 1024²、`adaptive-icon.png` 1024²（中央 66% 安全区）、`splash.png` 1284×2778 暗色底居中 BrandMark；
  美术为占位（accent 底 + 近黑几何），正式品牌图替换同名文件即可。
- `eas.json` 三 profile：`development`（模拟器：android apk / ios simulator）、`preview`（内测 apk，distribution internal）、
  `production`（android aab）。
- `package.json` scripts：`build:dev` / `build:preview`（经 `npx --yes eas-cli@latest build`，eas-cli 不装入依赖；
  需先 `npx eas-cli login` 与 `npx eas-cli init --id <projectId>`——app.json 暂无 projectId，首次构建时补齐）。
- 本地冒烟：`npx expo export -p android --dev`（验证整棵依赖图可打包）。

## 复用边界

- 业务逻辑全部来自 `@acme/core`，经 Metro `extraNodeModules` 指向 `../packages/core/src`，与 Web 共享同一 reducer/选择器/种子。
- store 与 Web 同构：Zustand 包裹 core 的 `reduce`/`loadState`/`saveState`，存储适配器为
  AsyncStorage（同步镜像 + 异步写穿透；启动经 `initMobileStorage` 冷启动 hydrate，会话仍走 expo-secure-store）。
- 不复用 Web 的 DOM/AntD 代码，也不复用 Electron 主进程/预加载。
- 图标用纯 `View` 组合绘制，不引入未登记的图标依赖。

## 常用命令（相对 `mobile/`）

| 用途 | 命令 |
|---|---|
| 启动 Metro | `pnpm start`（Expo，需模拟器或 Expo Go 设备扫码） |
| Android | `pnpm android` |
| iOS | `pnpm ios` |
| 类型检查 | `pnpm typecheck` |
| 测试 | `pnpm test` |
| EAS 构建（模拟器） | `pnpm build:dev` |
| EAS 构建（内测 apk） | `pnpm build:preview` |
| 本地打包冒烟 | `npx expo export -p android --dev` |

## 验证状态

- `tsc --noEmit` 通过；Vitest 66 用例通过（含 REQ-20261119-032108 scaffold 回归：主题双分支/边界重试/断网态/
  Toast/深链映射/i18n 成对/路由常量/AuthNavigator 登录登出联动）；`expo export -p android --dev` 成功打包
  （整棵依赖图含 `@acme/core` 源码、react-navigation v7 与 RN 原生导入可编译）。
- **真机/模拟器运行验证需 Android 模拟器、iOS 模拟器或 Expo Go 设备**，当前环境未提供，故未做运行时交互验证。
