# REQ-20261124-224523 设计器视觉与画布尺寸修复——AC→用例映射表 + 自验记录

> 用例文件（均在本 worktree 内）：
> - `web/tests/REQ20261124224523-designerVisual.test.tsx`（jsdom：组件形态/面板状态/页面装配/X6 装配接线面）
> - `web/tests/REQ20261124224523-designerVisualModel.test.ts`（node：模型纯函数/令牌/i18n/样式文本面）
>
> 断言口径：契约 AC + 确认①~④ + 委派单钉死事实；组件 DOM 断言取自黑盒渲染观测形态
> （事件=CSS 圆 div、网关=rotate(45deg)、边界事件=虚线圆、任务=圆角卡、fail 条目=code+定位按钮），
> 未读实现内部逻辑推导预期值；X6 链路经 vi.mock('@antv/x6') 库层桩（FakeGraph Proxy）捕获公开装配面
> （Graph 构造参数 / graph.on / graph.bindKey / 插件实例化 / react-shape register / Graph.registerAnchor·registerNode 静态注册 / 节点 add·fromJSON 元数据【后三项 delta-r1】），canvasFactory 保持真实模块。

## 一、AC→用例映射（AC-1~AC-14，每条 ≥1 用例或注明走查/SKIP）

| AC | 契约要点 | 用例名（文件：tsx=designerVisual.test.tsx / model=designerVisualModel.test.ts） | 面 |
|----|----------|----------------------------------------------------------------------------------|-----|
| AC-1 | 根 div 宽高=node 像素值；null 回退默认；禁 height:100% | tsx `should_match_root_div_to_node_pixel_size_when_node_prop_given`、`should_fall_back_to_default_pixel_size_when_node_prop_null`；model `should_cover_every_palette_type_when_default_sizes_queried`、`should_reserve_label_area_for_events_and_gateways_when_sizes_defined` | jsdom+模型 |
| AC-2 | 事件圆/网关菱形/任务圆角矩形；标签在图形下方；色走令牌；Palette 分组 | tsx `should_render_event_as_circle_when_start_event_shown`、`should_render_gateway_as_diamond_when_xor_shown`、`should_render_task_as_rounded_rect_when_user_task_shown`、`should_render_boundary_event_as_dashed_ring_when_boundary_shown`、`should_place_name_label_below_shape_when_event_rendered`、`should_use_token_variables_without_hardcoded_hex_when_rendered`、`should_color_palette_with_canvas_tokens_when_palette_rendered`、`should_render_three_group_titles_when_palette_rendered`；tsx（delta-r1）`should_render_full_name_label_without_clipping_when_event_rendered`（标签不得 ellipsis 拼截断） | jsdom |
| AC-3 | 不可自由缩放变形；尺寸单源 DEFAULT_NODE_SIZES | model `should_cover_every_palette_type_when_default_sizes_queried`、`should_reserve_label_area_for_events_and_gateways_when_sizes_defined`、`should_keep_subprocess_capacity_when_sizes_defined`；tsx `should_never_instantiate_transform_plugin_when_canvas_created`（Transform 仅移除使用） | 模型+接线 |
| AC-4 | 列距≥240/行距≥150；含子流程不重叠；缺位不叠 {0,0} | model `should_position_every_node_when_layout_missing`、`should_avoid_zero_zero_pileup_when_positions_missing`、`should_keep_column_gap_240_and_row_gap_150_when_mixed_model_laid_out`、`should_not_overlap_any_two_nodes_when_mixed_model_laid_out` | 模型 |
| AC-5 | grid alpha 上调（基线 light 0.08/dark 0.07）；发丝级不破 UI-SPEC | model `should_raise_grid_alpha_above_baseline_when_canvas_palette_resolved`、`should_resolve_palette_tokens_for_both_themes_when_canvas_themed`；**肉眼可辨/发丝级观感=浏览器走查** | 模型 |
| AC-6 | minimap 底色随令牌（无白底块） | model `should_theme_minimap_with_css_variables_when_global_css_loaded`；tsx `should_register_minimap_plugin_when_minimap_container_given`；**暗/亮观感=浏览器走查** | 样式+接线 |
| AC-7 | idle/pass 一行紧凑条（无 Empty）；fail 逐条 nodeId+message+onLocate；列表内滚有上限 | tsx `should_render_compact_status_bar_when_status_idle`、`should_render_compact_status_bar_when_status_pass`、`should_render_items_with_node_id_and_message_when_status_fail`、`should_invoke_on_locate_when_fail_item_clicked`、`should_cap_fail_list_scroll_when_many_items_rendered` | jsdom |
| AC-8 | 高度口径 100vh−184px | tsx `should_size_root_to_available_height_when_designer_opened` | jsdom |
| AC-9 | 工具栏 左=流程名+状态Tag+脏标记/右=按钮组；刷新=ReloadOutlined+aria-label(toolbar.refresh)可点击；保存/部署 deploy(79) 分控 | tsx `should_assemble_toolbar_zones_and_refresh_button_when_designer_opened`、`should_hide_save_and_deploy_when_user_lacks_deploy_permission`、`should_show_save_and_deploy_when_user_has_deploy_permission`、`should_expose_whitelist_palette_items_when_palette_rendered`、`should_set_acme_dnd_mime_when_palette_item_dragged`（DnD MIME 既有回归）；**脏标记触发联动/Tooltip 悬浮=浏览器走查**（DOM 断言已覆盖分区与刷新面） | jsdom |
| AC-10 | drop 落点节点中心对齐鼠标 | **SKIP（浏览器走查）**：落点几何为 X6 真实画布行为，jsdom 无法构造拖放坐标换算；Palette 侧 MIME 既有回归已在 AC-9 用例覆盖 | 走查 |
| AC-11 | 只读流程图节点尺寸/形状与交互态一致，无回归 | tsx `should_render_readonly_canvas_with_same_node_shape_when_detail_renders`（共用 acme-bpmn-node 形状 + 布局缺省 autoLayout 兜底不叠点）；**观感回归=浏览器走查（既有 102730 套件归 TL 门禁全量）** | 接线 |
| AC-12 | 门禁 typecheck/test/build 全过 + 两主题截图留档；i18n zh/en 新键齐备 | model `should_define_toolbar_refresh_key_in_both_locales_when_toolbar_rewired`、`should_define_palette_group_keys_in_both_locales_when_groups_added`、`should_keep_designer_i18n_key_sets_in_parity_when_keys_added`；**typecheck/build/截图=TL 门禁侧（本轮自验白名单仅限两用例文件）** | 模型+门禁 |
| AC-13 | ①端点止于边界 ②条件流×/默认流斜杠/边名 ③边 hover/选中令牌高亮 ④Delete 删除 | tsx `should_stop_edges_at_node_boundary_when_canvas_created`（connectionPoint=boundary）、`should_bind_delete_key_for_removal_when_canvas_interactive`（delete/backspace 绑定）；tsx（delta-r1）`should_assemble_edge_start_anchor_ports_when_node_added_interactively`（交互态节点 add 四向 ports/magnet 起线面）、`should_not_carry_edge_start_anchor_ports_when_readonly_assembled`（只读装配不携带）、`should_return_absolute_shape_center_from_registered_anchor_when_node_given`（SHAPE_CENTER_ANCHOR 回调返回绝对坐标）；**②标记与边名渲染/③hover·选中态/④实际删除行为=浏览器走查**（确认②③钉死行为面，jsdom 不建 X6 交互用例） | 接线 |
| AC-14 | 空白拖动平移（无新依赖）；缩略图仅总览；多选降级 | tsx `should_enable_blank_drag_pan_when_canvas_interactive`（panning 装配，readOnly 不启用）；**平移手感/缩略图职责/Shift 框选降级=浏览器走查**（确认④实现约束已实证于装配面） | 接线 |

## 二、自验记录（如实）

**命令与环境**：worktree `workspace/apps/frontend/web` 下 `pnpm exec vitest run tests/REQ20261124224523-designerVisual.test.tsx tests/REQ20261124224523-designerVisualModel.test.ts --no-file-parallelism`（node_modules 已存在，未触发 pnpm install；符合委派单自验白名单：仅本轮两用例文件，未跑全量/他人文件，未碰 git）。

**最终结果（2026-11-25 00:33 版本实测）**：
```
✓ tests/REQ20261124224523-designerVisual.test.tsx      (29 tests)
✓ tests/REQ20261124224523-designerVisualModel.test.ts  (16 tests)
Test Files  2 passed (2)   Tests  45 passed (45)
```
仅 stderr 提示 antd `destroyOnClose` deprecation warning（既有页面告警，非本用例失败因素）。

**迭代过程归因（5 轮自验，全部就地修复复验）**：
1. 轮1（model 13/16）：①保存载荷断言期望数组未排序（用例 bug，就地修）②minimap CSS 断言过严——存在纯 reset 块（box-shadow:none），改为「块存在+无 hex+合并体含 var(--)」三段式 ③palette.group.* i18n 键缺失=契约红（见下条时序）。
2. 轮2~3（tsx）：X6 库层桩缺口三轮补齐——`Graph.registerAnchor` 静态面（Proxy 吸收）、`renderReadOnlyBpmn` 5 参公开签名、`getCellById` 需按已建 cell 索引查（桩谎报 cell 已存在导致 sync 跳过创建）、`batchUpdate(回调)` 需真执行回调、`fromJSON({cells})` 落账；另有我的 3 处用例 bug：`renderDesigner` 返回值误解构、`within` 未导入、括号配平。
3. 轮4~5（并发改动回归）：本轮自验期间被测实现多次落盘（designerModel 23:27 / canvasFactory 23:34→00:32 / DesignerPage 23:31 / NodeShape+ValidationPanel 00:28），NodeShape 由 SVG 形态改为 CSS 形态、ValidationPanel 滚动上限载体变化、palette 分组键落地——按黑盒重探针重钉断言（DOM 形态识别改走 style 面：圆=borderRadius 50%、菱形=rotate(45deg)、边界=虚线圆、任务=圆角卡；内滚容器断言兼容面板根/列表双载体）。
4. 早期红灯「palette.group.* 缺失」系前端同轮并行落盘竞态，最终版本 zh/en 资源包均已含 `designer.palette.group.{events,gateways,activities}`，用例转绿。

**SKIP/走查清单（原因）**：
- AC-10：jsdom 无法构造真实拖放落点几何 → 浏览器走查。
- AC-13②③④、AC-14 行为面：X6 边标记渲染/hover 态/Delete 实删/平移手感/缩略图职责 → 浏览器走查（接线面已钉）。
- AC-5/AC-6 观感项、AC-2 标签「完整可读」观感、AC-9 脏标记触发链与 Tooltip 悬浮：截图走查。
- AC-12 的 typecheck/build/两主题截图：TL 门禁职责，tester 未越权代跑。

**遗留提示（供 TL III-TEST 参考）**：
1. 本轮实现为「移动靶」：最终 45/45 对应 00:33 落盘版本；III-GATE 前建议实现冻结后全量复跑（本轮白名单仅限两用例文件，未验全量回归）。
2. `renderReadOnlyBpmn` 签名 5 参（graph/input/palette/dark/labels）为既有公开面扩展，ApprovalDetailDrawer/InstanceMonitorTab 若有第三处调用点需同步适配（接线面用例已按新签名覆盖只读路径）。

## 三、delta-r1 追加（2026-11-25 tester 打回轮）

### 3.1 追加用例与钉死口径（全部落在 tsx；model 文件本轮无新增，原因见 3.4）

| # | 用例（均在 designerVisual.test.tsx） | AC | 钉死口径 |
|---|---|---|---|
| 1 | `should_assemble_edge_start_anchor_ports_when_node_added_interactively` | AC-13① | 交互态节点 add 元数据携带 ports（四向 items/groups）或任意层级 magnet 配置；观测面=交互态模型同步（DesignerPage 装载 bpmnXml→syncModelToGraph→addNode，捕获 4 节点装配）；DnD 落点几何仍=AC-10 浏览器走查 |
| 2 | `should_not_carry_edge_start_anchor_ports_when_readonly_assembled` | AC-13① | 只读装配（renderReadOnlyBpmn 路径）节点元数据不携带 ports/magnet（只读无连线创建入口；ports 挂共用注册面会连带只读节点，故正例钉在节点 add 面而非注册面） |
| 3 | `should_return_absolute_shape_center_from_registered_anchor_when_node_given` | AC-13① | Graph.registerAnchor 注册的 SHAPE_CENTER_ANCHOR 回调对 node 桩（getPosition/getSize/getData/getBBox/isNode）返回绝对坐标=bbox 原点+图形区中心补偿（任务=整格中心；事件/网关=图形高 40/50 的一半补偿）；库契约仲裁见 3.2 |
| 4 | `should_render_full_name_label_without_clipping_when_event_rendered`（委派单钉死命名） | AC-2 | 事件/网关名称标签元素不含 text-overflow: ellipsis 拼截断，且 overflow visible 或标签区宽不受节点宽约束（覆盖 Start/End/XOR/AND/Boundary 五类） |
| 5 | `should_observe_canvas_container_via_auto_resize_when_graph_created` | 走查附带 | Graph 构造参数含 autoResize 且指向画布容器（非布尔 true） |

### 3.2 X6 库契约仲裁（委派单歧义处置记录，供 TL 备案）

委派单钉死「anchor 回调返回绝对坐标」与当时实现（返回节点局部坐标：任务半宽 64 vs 期望 164）冲突。经 X6 库源码仲裁（node_modules/@antv/x6 依赖契约，非实现内部）：
- 内置 node-anchor 全部返回图坐标点：`bbox.ts` 用 `view.getBBoxOfElement(magnet)`（graph 坐标系）取 center 等；`node-center.ts` 用 `cell.getConnectionPoint`（绝对 bbox 中心）。
- EdgeView `getAnchor` 返回值直接作为 sourceAnchor/targetAnchor 进入路由与 connectionPoint 计算，无局部→绝对转换。
- **结论：绝对坐标口径与 X6 库契约一致，返回局部坐标为缺陷**（会致边端点锚到图原点附近）；后实现侧修复 2 亦以同一依据落盘，双向印证。断言全程未让步。

### 3.3 桩扩展清单（不改断言语义，委派单允许）

- FakeGraph 捕获面：addNode/addCell 首参落账 `nodeAddMeta`、fromJSON cells 落账 `loadCellMeta`（随测 reset）；Graph 静态 `registerAnchor`/`registerNode`/`registerEdge` 落账捕获（`anchors`/`staticRegisters`，同 registerAll 口径持久）。
- `@antv/x6` mock 补最小 `Point` 桩（x/y + create/round/translate/equals/distance；锚点回调以 Point 构造返回值，断言只读 x/y）。
- 锚点 node 桩语义补 `isNode()=true`/`isEdge()=false`、`cell` 属性自引（容忍 view.cell 调用约定）；调用面 node 直传/view.cell/this 三口径兼容，断言只钉返回点坐标。

### 3.4 文件归属说明

四个追加面全部为 X6 装配/组件 DOM 面（canvasFactory 需 vi.mock 隔离才能在 jsdom 收集，node 环境收集期抛 Element is not defined），故全部落在 designerVisual.test.tsx；designerVisualModel.test.ts（纯 TS 模型面）本轮无对应公开面可钉，未改动（既有 16 例不受影响）。

### 3.5 delta-r1 自验记录（如实，命令同白名单）

四轮实测：
1. 轮1（08:42）：46/50——既有 45 例全绿；delta-r1 五例中只读负例绿，其余 4 红：ports 正例（捕获 4 节点装配无 ports/magnet）、anchor（桩缺口 TypeError isNode→修桩）、autoResize（构造参数缺失）、标签（StartEvent inline text-overflow: ellipsis）。
2. 轮2（修桩后）：仍 46/50，anchor 卡 mock 缺 `Point` 导出→补 Point 桩。
3. 轮3：仍 46/50，anchor 分歧清晰化——任务锚点实现返回 x=64（节点局部半宽）vs 期望 x=164（bbox 原点 100+半宽）；另 3 红同前。归因：实现侧 delta-r1 修复尚未落盘（移动靶）；经 grep 仅归因后确认实现四项修复刚落盘。
4. 轮4（09:00）：**50/50 全绿（34+16）**——实现侧四项修复落盘（bpmnPortsOf 四向 ports+readOnly ports:false / anchor 改 getBBox() 绝对坐标+图形高补偿 / autoResize: container / NodeShape 标签 overflow visible）后断言零改动转绿（仅桩扩展，见 3.3）。

**待实现对齐项清单：无遗留**——轮1~3 的 4 项红灯均系实现修复落盘前的时间差，轮4 实现落盘后全部转绿；anchor 坐标系歧义经库契约仲裁后与委派单钉死口径一致（见 3.2）。

**遗留提示（供 TL III-TEST 参考）**：
1. 本轮自验窗口内实现持续落盘（08:42→09:00 间四项修复到位）；最终 50/50 对应 09:00 实测，建议 III-GATE 前实现冻结后复跑。
2. 锚点回调调用约定（view.cell/isNode/Point 构造）已在桩侧按公开约定容忍；实现若再调回调签名只需同步桩，断言语义不变。
