# 页签条迭代轮次 2（折叠/缩略图/滚轮/阴影）性能与难点分析

> 状态：已完结（2026-11-20）。来源：用户需求（2026-11-20）+ 现状代码勘察。
> 拍板与落地：① 用户改判「不做折叠，直接滚动」＋加箭头按钮——随 REQ-20261120-104022 落地
> （分隔符/箭头/滚轮/阴影/结构化预览卡，prod-ee7a6f3 已上线）；② 预览卡取结构化路线
> （真截图 desktop capturePage「离开时截图」路线留作后续需求，本稿路线 1/2 分析保留作依据）；
> ③ 依赖本稿分析衍生的基础设施：env-stack 增量发布机制（ee7a6f3，ACME_*_SERVICES）。
> 详细裁决与门禁台账见主工作树 contracts/REQ-20261120-104022.md（git 忽略，仅本机留存）。

## 现状事实（决定方案成本）

- 自绘页签条 `web/src/components/TabBar.tsx`；`.tabbar` 已 `overflow-x:auto` + `scrollbar-width:none`（`web/src/styles/global.css:242`）；tab `flex:none` 固定宽，label 160px ellipsis（global.css:244,248）。
- 非激活页签 DOM `display:none` 全量保活（`web/src/components/TabKeepAlive.tsx:38`）→ capturePage 截不到后台、DOM 快照可 clone。
- desktop = Electron 37（Chromium 118+）单窗口套壳，无 webview/多 BrowserView；`:has`/容器查询/scrollend 可用。
- web=React18+AntD5(cssinjs)+zustand+i18next；测试 Vitest+jsdom，已有"CSS 源级视觉锚"模式（REQ20261119063129-tabs.test.tsx）。

## 各项结论

1. **分隔符**：纯 CSS（`+::before`）；相邻 hover/active 淡出需 `:has(+ …)`；折叠态下分隔符变必需，与折叠档位在同一处 CSS 联动。性能可忽略。
2. **逐步折叠（核心）**：
   - 推荐路线 A＝纯 CSS：`flex: 0 1 auto; min-width; container-type: inline-size` + `@container` 分档（缩 label→隐 label→隐关闭钮→favicon），到 min-width 溢出交给既有滚动。零 JS、无 layout thrashing、天然无振荡（JS 路线必须加迟滞余量）。
   - 代价：分档跳变（可 opacity 淡出缓解）；均匀收缩≠Chrome 式逐个折叠；活动签保读性可用 `flex-shrink:0` 近似。精确逐个折叠才需要路线 B（RO 测量+state，O(n) reconcile+振荡+宽度缓存失效）。
   - `container-type` containment 影响：tab 内部简单，实施时验证。
   - **滚动模式必现新问题**：活动签视口外 → 需 `scrollIntoView({inline:'nearest'})`（轮次 1 无此问题）。
3. **滚轮**：局部 `wheel` passive:false + `preventDefault`；必须放行 Ctrl/Cmd（缩放）；deltaMode 归一；触控板 deltaX 不二次加速（取绝对值大者）；只在溢出时挂；不挂 window。RTL 记录为风险。键盘可达性+方向键滚动一并补。
4. **hover 缩略图（最大难点，架构缺口）**：SPA+保活无现成截图能力。
   - 路线 1 真截图：desktop 用"**离开时 capturePage**"（切签瞬旧签可见；mousedown 预截避免截到新页；所有切换调用点统一预截，desktop 加 IPC）。内存 320×200 JPEG×50≈2~3MB，最优。web 端无 API（html2canvas 100~500ms 主线程+失真，不推荐）→ 两端行为不一致需产品拍板。
   - 路线 2 DOM 快照+沙箱 iframe：两端一致；内存 20~100MB+（LRU 上限）、cssinjs/令牌同步脆弱、echarts canvas 丢失需补丁、hover 首显 100~300ms。
   - 路线 3 结构化预览卡（图标+全标题+操作）：成本最低，VS Code 类比，首版推荐。
   - 通用纪律：生成只在切签时（idle+串行队列）；hover 状态放组件本地，不进 zustand；Portal+合成器动画；debounce 100~150ms；去掉与预览卡重叠的 `title={label}`。
5. **端点阴影**：绝对定位伪元素渐变（勿 mask-image 套滚动容器）；驱动推荐 JS 布尔翻转（rAF 节流，翻转时写 class，可抽纯函数单测）；CSS `animation-timeline: scroll(self)` 可作后续增强（jsdom 不可测）。

## 性能预算（20~50 签）

折叠走 CSS、hover 不出 React 树时，滚动/折叠/阴影常态主线程成本≈0；全部风险集中于缩略图：真截图 2~3MB / DOM 快照 20~100MB+ / 结构化卡≈0；DOM clone 10~50ms、iframe 首渲染 100~300ms、capturePage 几十 ms（均须 idle 串行）。

## 待拍板

- [ ] 缩略图路线：结构化卡（首版稳）/ desktop 真截图+web 降级卡 / DOM 快照重放
- [ ] 折叠策略：CSS 均匀收缩（推荐）vs JS 逐个折叠（要 Chrome 式才值得）
- [ ] 是否要左右箭头按钮（参考图 Firefox 有，需求只写了滚轮）
