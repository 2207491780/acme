# 移动端 UI 采用自绘 RN 原生组件（取代 02 号决策的 antd-mobile 条目）

日期：2026-11-12
取代：`2026-11-11-02-frontend-react-stack.md` 中「移动……UI 用 antd-mobile」一条；该决策其余内容（React + TS 底座、monorepo、Electron 复用 Web 产物等）仍然有效。

## 背景

02 号决策记录移动端 UI 选型为 antd-mobile，但 antd-mobile 是基于 DOM 的 Web 组件库，在 React Native 原生渲染体系下无法运行（RN 无 DOM）。实施时发现该条目技术性不可行，实际实现（`mobile/src/components/`）采用自绘 RN 组件复刻 Ant Design 视觉语言。按 ADR「只增不改」纪律补记本决策。

## 决定

- 移动端**不引入 antd-mobile**；UI 用 React Native 原生组件自绘（Card / Tag / Button / ProgressBar 等），视觉对齐 Ant Design 风格。
- 视觉一致性由 `packages/core/src/theme.ts` 设计令牌保证：Web 消费 `*_ANTD` 预设色名（AntD 算法渲染），Mobile 直接消费 `TONES` / `*_COLORS` hex，语义一致、渲染各自 idiomatic。
- `mobile/mobile.md`、`frontend.md`、`UI-SPEC.md` 均按此表述；`.ai/rules/project.md` 已登记「未接入 antd-mobile」。

## 被否的选项

- **antd-mobile**：DOM 组件库，RN 下无法渲染，技术性不可行（02 号决策笔误根源）。
- **react-native-ui-lib / NativeBase 等第三方 RN 组件库**：需登记新依赖且视觉体系与 AntD 不对齐；现仅四个屏，自绘组件量小、成本可控，且令牌完全可控。
