# mobile — 移动端

React Native + Expo 移动应用，复用共享 `@acme/core` 业务层，实现与 Web 同构的「ACME 多端前端交付台」。UI 为 **Ant Design 风格的 RN 原生组件**（非 `antd-mobile`——后者基于 DOM，与 RN 原生渲染不兼容），共享 Zustand store 模式与同一套发布单业务逻辑。

## 技术栈

Expo SDK 52（expo ~52.0.49 / react 18.3.1 / react-native 0.76.9）+ Zustand 5 + TypeScript 5；Vitest 单测；Metro  monorepo 配置直接复用 `@acme/core` 的 TS 源码。`expo-asset` / `expo-font` 为 Expo SDK 内置基线包，显式声明以便 pnpm 严格隔离下 Metro `getDefaultConfig` 可解析。

## 目录结构

```text
mobile/
├── mobile.md
├── app.json                # Expo 配置（深色、scheme acme、newArchEnabled）
├── index.ts                # registerRootComponent(App)
├── App.tsx                 # 头部 + 自定义底部 Tab 导航 + 构建 TICK 循环
├── package.json            # @acme/mobile
├── babel.config.js         # babel-preset-expo
├── metro.config.js         # monorepo：watchFolders / nodeModulesPaths / @acme/core→src
├── tsconfig.json           # extends expo/tsconfig.base，@acme/core paths 别名
├── vitest.config.ts
├── src/
│   ├── nav.ts              # TabKey / ScreenProps
│   ├── theme.ts            # 与 Web 一致的设计令牌（强调色/语义色/平台色）
│   ├── hooks.ts            # useAccent / useOnAccent
│   ├── store/useStore.ts   # Zustand 包裹 core reducer（与 Web 同构）
│   ├── utils/storage.ts    # StorageLike 内存适配器（生产接 AsyncStorage）
│   ├── components/         # ui.tsx（Card/Tag/Button/ProgressBar…）/ icons.tsx（纯 View 矢量图标）
│   └── screens/            # Dashboard / Releases / Apps / Settings
└── tests/                  # Vitest：store（9 用例）/ theme（2 用例）
```

## 复用边界

- 业务逻辑全部来自 `@acme/core`，经 Metro `extraNodeModules` 指向 `../packages/core/src`，与 Web 共享同一 reducer/选择器/种子。
- store 与 Web 同构：Zustand 包裹 core 的 `reduce`/`loadState`/`saveState`，仅替换存储适配器（脚手架用内存态，生产启动时由 AsyncStorage 异步 hydrate）。
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

## 验证状态

- `tsc --noEmit` 通过；Vitest 11 用例通过；`expo export -p android` 成功打包（564 模块 → Hermes 字节码），证明整棵依赖图（含 `@acme/core` 源码与 RN 原生导入）可编译。
- **真机/模拟器运行验证需 Android 模拟器、iOS 模拟器或 Expo Go 设备**，当前环境未提供，故未做运行时交互验证。