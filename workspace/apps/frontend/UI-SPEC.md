# UI-SPEC — 多端一致设计规范（AI 实施契约）

> 读者：实现/修改前端界面的 AI。本文件是**可执行契约**，不是参考散文。
> 唯一事实源：[`packages/core/src/theme.ts`](packages/core/src/theme.ts)（导出经 `@acme/core`）。
> 配套校验页：[`ui-spec/style-guide.html`](ui-spec/style-guide.html)（由 `pnpm ui:guide` 生成）。
> 适用端：Web（Ant Design 5）、PC（Electron 套壳 Web，规范同 Web）、Mobile（React Native + Expo）。
> 视觉基调：**耐看、简约、低饱和**——暖调高级灰中性色 + 去饱和柔和强调色；不使用霓虹/光晕/彩虹渐变与动画光斑，渐变只在同色系内做极轻微明度变化，描边发丝级、阴影柔和。

---

## 0. 铁律（DO / DON'T）

| | 规则 |
|---|---|
| ✅ DO | 一切色值/尺寸/字体/动效时长 **从 `@acme/core` 令牌取**；新增令牌先改 `core/src/theme.ts` 再派生。 |
| ✅ DO | Web 用 `buildTheme(settings)` 注入 AntD `ConfigProvider`；语义标签用 `*_ANTD` 预设色名映射。 |
| ✅ DO | Mobile 用 `mobile/src/theme.ts` 的 `palette / TONE_COLOR / ...`（均已派生自 core）。 |
| ✅ DO | 改任何令牌后跑 `pnpm -r test && pnpm ui:guide`，并在校验页核对 Web/Mobile 两栏。 |
| ❌ DON'T | 在组件、CSS、StyleSheet 里**硬编码**已存在的令牌色值/尺寸（如 `#5aa7a0`、`16`）。 |
| ❌ DON'T | 为单端引入新的颜色体系或第二套强调色；六色强调是全局唯一可选集。 |
| ❌ DON'T | 把语义 hex 直接塞进 AntD `Tag color`（会破坏暗色算法）；用 `TONE_ANTD` 等预设名。 |
| ❌ DON'T | 在 Mobile 引入未登记依赖来实现渐变/图标；当前 Mobile 用纯 `View` 绘制，无渐变。 |
| ❌ DON'T | 重新引入高饱和霓虹色、光晕（glow）、彩虹多色渐变或背景漂移动画；视觉须保持低饱和克制。 |
| ❌ DON'T | 修改派生 AI 配置文件来"记录"本规范；本文件是应用级设计文档，不进 `.ai/sync`。 |

---

## 1. 如何取令牌（三端入口）

```ts
// 任意端：框架无关令牌
import {
  ACCENTS, ACCENT_ORDER, ON_ACCENT, ACCENT_COMPANIONS, BRAND_HUES,
  TONES, TONE_ANTD, PLATFORM_COLORS, PLATFORM_ANTD,
  ENV_COLORS, ENV_ANTD, HEALTH_COLORS, HEALTH_ANTD, HEALTH_LABELS,
  DARK_NEUTRALS, LIGHT_NEUTRALS, resolvePalette,
  SPACING, RADII, FONT_FAMILY, FONT_SIZE, FONT_WEIGHT, LINE_HEIGHT, LETTER_SPACING,
  MOTION, SHADOWS, Z, DENSITY, GRADIENTS,
  withAlpha, mixHex, statusColor,
} from '@acme/core';
```

```tsx
// Web / PC：AntD 主题（web/src/theme.ts，已派生）
import { buildTheme } from './theme';
<ConfigProvider locale={zhCN} theme={buildTheme(settings)}>…</ConfigProvider>
// CSS 变量层 web/src/styles/global.css 镜像 core 中性/强调色，仅承载轻量表现层（发丝描边/柔和阴影/动效）。
```

```tsx
// Mobile：RN 令牌（mobile/src/theme.ts，已派生，导出名沿用历史 API）
import { palette, TONE_COLOR, PLATFORM_COLOR, ENV_COLOR, HEALTH_COLOR, HEALTH_LABEL, ACCENTS } from '../theme';
const accent = ACCENTS[settings.accent]; // 或 useAccent() hook
```

**派生关系**：`core/theme.ts` →（程序派生）→ `web/theme.ts` + `mobile/theme.ts` →（消费）→ 组件。
`global.css` 与校验页**镜像**同一组值；core 改动后需同步核对（校验页自动反映）。

---

## 2. 令牌参考

### 2.1 强调色（唯一可选集，顺序即 UI 顺序）

| Accent | hex | on-accent（实心填充上的前景） | 渐变 secondary | 渐变 tertiary |
|---|---|---|---|---|
| `cyan`（默认） | `#5aa7a0` | `#000000` | `#6f92c4` | `#9188c9` |
| `blue` | `#6f92c4` | `#000000` | `#5aa7a0` | `#9188c9` |
| `violet` | `#9188c9` | `#000000` | `#bd7ba0` | `#5aa7a0` |
| `magenta` | `#bd7ba0` | `#000000` | `#cfa14f` | `#6f92c4` |
| `amber` | `#cfa14f` | `#000000` | `#9db56a` | `#5aa7a0` |
| `acid` | `#9db56a` | `#000000` | `#6fa287` | `#6f92c4` |

> 强调色均为去饱和中间明度，`ON_ACCENT` 统一为纯黑 `#000000`：Web 暗色算法会把主色压暗，纯黑前景保证六色在暗/亮两态均 ≥ 4.5:1（最暗的 blue 暗色约 5.1:1）。

扩展品牌色（不可选，仅图标/点缀）：`teal #6fa287`、`red #c47b7b`、`slate #8b929e`（`BRAND_HUES`）。

### 2.2 语义色（Tone）——跨端语义一致，渲染 idiomatic

| Tone | 规范 hex（`TONES`，Mobile 直接渲染） | AntD 预设名（`TONE_ANTD`，Web 渲染） | 含义 |
|---|---|---|---|
| `muted` | `#8b929e` | `default` | 草稿/已取消/次要 |
| `info` | `#6f92c4` | `processing` | 构建中/信息 |
| `violet` | `#9188c9` | `purple` | 测试中 |
| `warning` | `#cfa14f` | `warning` | 待发布/关注 |
| `success` | `#6fa287` | `success` | 已发布/健康 |
| `danger` | `#c47b7b` | `error` | 失败/异常/生产 |

> Web 保留预设名，由 `buildTheme` 将 `colorSuccess/colorWarning/colorError` 与 `purple` 种子设为 `TONES`，与 Mobile 共享低饱和色彩基准。AntD 在明暗主题下进一步派生前景、背景与边框色，因此最终像素值不要求与 Mobile 相同；校验页展示基准色示意，实际派生值由 Web 主题测试锁定。**禁止**让这些预设名回落到 AntD 鲜艳出厂色。

状态→Tone 由 `statusTone(status)` 决定；取色用 `statusColor(status)`（=`TONES[statusTone]`）。

| Status | draft | building | testing | approved | published | failed | cancelled |
|---|---|---|---|---|---|---|---|
| Tone | muted | info | violet | warning | success | danger | muted |

### 2.3 分类色（Platform / Env / Health）

| 维度 | 键 → hex（Mobile） | 键 → AntD 名（Web） |
|---|---|---|
| Platform | web `#5aa7a0` · desktop `#9188c9` · mobile `#6fa287` | web `processing` · desktop `purple` · mobile `success` |
| Env | dev `#8b929e` · test `#cfa14f` · prod `#c47b7b` | dev `default` · test `warning` · prod `error` |
| Health | healthy `#6fa287` · warning `#cfa14f` · critical `#c47b7b` | healthy `success` · warning `warning` · critical `error` |

Health 文案（`HEALTH_LABELS`）：healthy `健康` · warning `关注` · critical `异常`。
Platform 文案：`platformShort` → Web/Electron/Mobile；`platformLabel` → Web 端/PC 桌面端/移动端。Env：`envLabel` → 开发/测试/生产。

### 2.4 中性色（`resolvePalette(theme)`）

| 令牌 | Dark | Light | 用途 |
|---|---|---|---|
| `bg` | `#14161a` | `#f7f8f9` | 应用背景（AntD `colorBgBase/Layout`） |
| `bg1` | `#191c21` | `#f1f2f4` | 次级背景/树视图底 |
| `surface` | `#1d2127` | `#ffffff` | 卡片/容器（AntD `colorBgContainer`） |
| `elevated` | `#242932` | `#f4f5f7` | 抬升面/表头（AntD `colorBgElevated`） |
| `surface3` | `#2c323c` | `#e9ebee` | 进度轨道/悬停块/滚动条 thumb |
| `text` | `#e8eaed` | `#1c2128` | 主文本（AntD `colorText`） |
| `text2` | `#b6bcc6` | `#4a5261` | 次文本（AntD `colorTextSecondary`） |
| `muted` | `#8b929e` | `#7a8290` | 弱文本（暗色 =`TONES.muted`） |
| `border` | `rgba(255,255,255,0.08)` | `rgba(20,24,32,0.10)` | 默认边框（AntD `colorBorder`） |
| `borderSecondary` | `rgba(255,255,255,0.05)` | `rgba(20,24,32,0.06)` | 弱边框 |
| `glassSider` | `rgba(29,33,39,0.85)` | `rgba(255,255,255,0.85)` | Web 侧栏半透明表面 |
| `glassHeader` | `rgba(20,22,26,0.8)` | `rgba(247,248,249,0.8)` | Web 顶栏半透明表面 |
| `scrim` | `rgba(0,0,0,0.4)` | `rgba(20,24,32,0.25)` | 遮罩基色 |

> Mobile 当前**仅暗色**（`palette` 取 `DARK_NEUTRALS`）。Web/PC 支持暗/亮，由 `settings.theme` 切换。

### 2.5 尺寸 / 圆角 / 间距

| 组 | 令牌 → 值 |
|---|---|
| `SPACING` | xs 4 · sm 8 · md 12 · lg 16 · xl 24 · xxl 32 · xxxl 48 |
| `RADII` | sm 8 · md 12 · lg 16 · xl 20 · pill 999 |
| `DENSITY.comfortable` | radius 12 · cardPadding 20 · cellBlock 13 · cellInline 14 · gap 16 |
| `DENSITY.compact` | radius 8 · cardPadding 16 · cellBlock 8 · cellInline 10 · gap 12 |

### 2.6 字体 / 排版

| 组 | 令牌 → 值 |
|---|---|
| `FONT_FAMILY.sans` | `"Segoe UI", "PingFang SC", "Microsoft YaHei", system-ui, sans-serif` |
| `FONT_FAMILY.mono` | `"Cascadia Code", "JetBrains Mono", Consolas, monospace` |
| `FONT_SIZE` | caption 11 · small 12 · body 13 · base 14 · lead 16 · title 20 · h2 24 · h1 30 · display 38 |
| `FONT_WEIGHT` | regular 400 · medium 500 · semibold 600 · bold 700 · heavy 800 |
| `LINE_HEIGHT` | tight 1.1 · snug 1.3 · normal 1.5 · relaxed 1.7 |
| `LETTER_SPACING` | eyebrow 2.4 · brand 1.6 · tight -0.6 |

### 2.7 动效 / 层级 / 阴影 / 渐变

| 组 | 令牌 → 值 |
|---|---|
| `MOTION` | fast 120ms · base 200ms · slow 320ms · tick 1200ms · easing `cubic-bezier(0.22,1,0.36,1)` |
| `Z` | base 0 · sticky 20 · nav 30 · drawer 1000 · modal 1000 · toast 1010 |
| `SHADOWS` | card `0 8px 24px rgba(0,0,0,0.16)` · pop `0 4px 12px rgba(0,0,0,0.12)` |
| `GRADIENTS.brand(accent)` | `linear-gradient(135deg, accent, mixHex(accent,#000,0.82))`（同色系轻微明度变化） |
| `GRADIENTS.hero(accent,theme)` | `linear-gradient(160deg, mixHex(accent,surface,0.06), surface)`（极淡染色淡出到表面色） |
| `GRADIENTS.text(accent)` | `linear-gradient(120deg, accent, secondary)`（同族柔和过渡，低对比） |

辅助函数：`withAlpha(hex, a)`→`rgba(r, g, b, a)`；`mixHex(a, b, w)`→模拟 `color-mix(in srgb, a w%, b)`；`statusColor(status)`。
半透明叠加惯例：选中 `${accent}1f`、悬停/选中加深 `${accent}29`、标签底 `${color}1f`、标签框 `${color}66`。

---

## 3. 令牌 → 平台映射（实施速查）

| 概念 | core | Web / PC（AntD） | Mobile（RN） |
|---|---|---|---|
| 主色 | `ACCENTS[accent]` | `token.colorPrimary/colorInfo` | `useAccent()` / `ACCENTS[accent]` |
| 背景/容器 | `resolvePalette` | `colorBgBase/Container/Elevated` | `palette.bg/surface/elevated` |
| 文本 | `text/text2/muted` | `colorText/Secondary` + CSS `--text*` | `palette.text/text2/muted` |
| 边框 | `border/borderSecondary` | `colorBorder/Secondary` | `palette.border` |
| 圆角 | `DENSITY[d].radius` | `token.borderRadius` | `RADII.lg`(卡片16)/`RADII.md`(按钮12) |
| 语义标签 | `TONE_ANTD` / `TONES` | `<Tag color={TONE_ANTD[tone]}>` | `<Tag color={TONES[tone]}>` |
| 间距 | `SPACING` | CSS gap/padding | `StyleSheet` gap/padding |
| 字体 | `FONT_FAMILY/FONT_SIZE` | `token.fontFamily` + CSS | `StyleSheet` fontSize/fontWeight |
| 动效 | `MOTION` | CSS transition/animation | `Animated` duration（tick 由 `motion` 开关） |
| 渐变 | `GRADIENTS` | CSS `linear-gradient` | 纯色近似（无渐变依赖） |

---

## 4. 组件契约（两端必须等价）

> 通用：所有交互元素有可见反馈（hover/pressed/focus）；禁用态降低不透明度且不可点；文案用 core  label 函数，不自行拼中文。

### Button
- **Web**：AntD `Button`，`type="primary"` 用主色，文字经 `colorTextLightSolid = ON_ACCENT[accent]` 取纯黑（勿用默认白字；暗色算法压暗主色后仍 ≥4.5:1）；危险操作用 `danger`（描边态，文字 `TONES.danger`）；尺寸随 density。
- **Mobile**：`ui.tsx Button`，`variant: primary|ghost|danger`；primary 实心 accent + 前景 `ON_ACCENT`（纯黑 `#000000`，六色统一）；圆角 12（small 10）；padding 16×11（small 12×7）；字号 14/700（small 12）；pressed `opacity 0.65`。
- **共享**：primary 文字用 `ON_ACCENT`（深色）保证对比 ≥4.5:1；危险色 = `TONES.danger`。

### Tag / 标签
- **Web**：`<Tag color={预设名}>`（见 `*_ANTD`）；可带 `icon`。
- **Mobile**：`ui.tsx Tag`，边框 `${color}66`、底 `${color}1f`、文字 `color`；圆角 8；padding 8×3；字号 12/600。
- **共享**：Status/Platform/Env/Health 四类语义取色一致（§2.2/2.3）。

### Card
- **Web**：AntD `Card`，`paddingLG = DENSITY[d].cardPadding`；实底 `surface` + 发丝描边 `border`（不用玻璃透明底）。
- **Mobile**：`ui.tsx Card`，底 `palette.surface`、圆角 16、边框 1 `palette.border`、padding 16、gap 10。

### Progress
- **Web**：AntD `Progress` 或 `.bar-*`；轨道 `surface3`，填充语义/主色，圆角 pill。
- **Mobile**：`ui.tsx ProgressBar`，轨道高 6 `palette.elevated`，填充 `color`，圆角 999，`percent` clamp 0–100。

### Input / 搜索
- **Web**：AntD `Input`，`allowClear`，前缀搜索图标，`Ctrl/Cmd+K` 聚焦。
- **Mobile**：`TextInput`，底 `surface`、边框 `border`、圆角 12、placeholder `muted`。
- **共享**：搜索词进 `query.search`，过滤逻辑用 core `filterReleases`。

### Table / 列表
- **Web**：AntD `Table`，表头 `elevated`，行悬停 `withAlpha(cyan|blue,0.06)`，选中 `${accent}1f`；支持拖拽调序（`REORDER`）、批量选择。
- **Mobile**：`FlatList` + 可展开 `ReleaseCard`（无表格）；展开显示 notes/artifacts/timeline。
- **共享**：排序/筛选用 core `sortReleases/filterReleases`；调序写 `state.order`。

### Drawer / Modal
- **Web**：AntD `Drawer`（详情，右侧）/`Modal`（表单）；z 1000；遮罩 `scrim`。
- **Mobile**：全屏 Screen 切换 + 展开卡片替代抽屉/弹窗（无模态依赖）。
- **共享**：详情只读字段 + 状态流转按钮（`STATUS_ACTION`/`NEXT_STATUS`）。

### Nav / 导航
- **Web/PC**：固定 `Sider`（≥lg）+ 顶栏；<lg 用底部 `mobile-nav` + 左抽屉；当前项主色高亮，发布单带活跃数 `Badge`。
- **Mobile**：底部 tabbar（dashboard/releases/apps/settings），当前项 accent pill + 活跃数 badge；顶部品牌条。
- **共享**：四目的地一致；活跃数 = `ACTIVE_STATUSES` 计数。

---

## 5. 布局 / 响应式 / 安全区

| 项 | Web / PC | Mobile |
|---|---|---|
| 断点 | AntD `Grid.useBreakpoint()`；`<lg` 视为移动布局 | 原生小屏 |
| 顶栏 | sticky，高 64，半透明表面 + 轻微模糊（blur 8）+ 底边框 | 品牌条 + 运行时 pill |
| 内容 padding | 桌面 `24px 30px 60px`；移动 `16px 14px 88px` | Screen `padding 16, paddingBottom 32, gap 14` |
| 安全区 | 底部导航 `env(safe-area-inset-bottom)` | `SafeAreaView` + Android `StatusBar.currentHeight` |
| 背景 | 纯色 `bg`（平整，与 Mobile 一致），无光斑/漂移/颗粒 | 纯色 `palette.bg` |
| 滚动条 | 自定义（中性 `surface3` thumb，宽 10，轨道透明；hover 提亮） | 隐藏垂直滚动条 |

---

## 6. 动效 / 交互

- 时长取 `MOTION`：微交互 fast/base，进场 slow，构建进度 `tick`（1200ms）。
- `settings.motion=false` 或 `prefers-reduced-motion` → 关闭所有动画/过渡（Web 由 `data-motion` + 媒体查询；Mobile `TICK` 副作用 gated by `motion`）。
- 反馈：hover 边框变主色 + 轻微抬升（≤2px），无光晕；pressed 降不透明度；状态流转即时更新 + 活动流（`TONE_COLOR` 点 + `relTime`）。
- 破坏性操作（删除）必须可撤销（`RESTORE_RELEASES` + Undo Toast）。

## 7. 无障碍 / 对比

- 正文文本对比 ≥ 4.5:1（text/text2 on bg/surface）；弱文本 muted 仅用于次要信息。
- 实心 accent 上的文字用 `ON_ACCENT`（深色），勿用白字。
- Mobile 可点目标 ≥ 44×44px；按钮 padding 已满足，图标按钮需补足命中区。
- 所有图标按钮带 `aria-label`（Web）/ `accessibilityLabel`（Mobile）。
- 语义不仅靠颜色：状态/健康同时有文案标签。

## 8. AI 实施清单

1. 读 `core/src/theme.ts` 确认令牌名与值；缺令牌→先加 core 再派生。
2. Web 改样式：优先 AntD token/`buildTheme`；表现层（玻璃/渐变/动效）才进 `global.css`，色值镜像 core。
3. Mobile 改样式：用 `palette`/`TONES`/`SPACING`/`RADII`，纯 `View` 绘制，不引未登记依赖。
4. 语义标签：Web 用 `*_ANTD`，Mobile 用 `*_COLORS`/`TONES`。
5. 跑 `pnpm -r typecheck && pnpm -r test`；改令牌再跑 `pnpm ui:guide`。
6. 打开 `ui-spec/style-guide.html`，切换 6 强调色 × 暗/亮 × 两密度，核对 Web/Mobile 两栏一致、无 console 错误。
7. 真机/浏览器验证主流程（创建→流转→删除→撤销）后再交付。

## 9. 验证锚点

| 验证 | 命令 / 位置 |
|---|---|
| 令牌单测 | `packages/core/tests/theme.test.ts` |
| Web 主题不回归 | `web/tests/theme.test.ts`（锁定暗/亮 AntD 输出） |
| Mobile 主题派生 | `mobile/tests/theme.test.ts` |
| 跨端可视核对 | `pnpm ui:guide` → `ui-spec/style-guide.html` |
| 全量 | `pnpm -r typecheck && pnpm -r test` |