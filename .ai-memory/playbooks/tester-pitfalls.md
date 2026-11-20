# tester 踩坑卡（历轮打回归因沉淀；开工必读，写用例前对照自查）

> 触发：tester 角色输入清单 + 委派单「钉死事实」段。新增条目由 TL 在打回轮归因后追加（只增条目，不改结论），追加时在契约裁决记录标注「已沉淀 pitfalls 卡」。
> 收编来源：REQ-20261120-213603（门禁 7 轮，tester 用例占 5 轮）、REQ-20261120-214653（3 轮全为用例修复）台账；8 例打回全是环境事实盲猜，零规格理解错误。

## 后端框架事实

1. **PageQuery 分页上界 MAX_SIZE=200**：构造 `size>200` 在构造器直接抛 `InvalidRequestException`（REQ-214653 六 Error 教训）——正常筛选/分页用例一律 `size≤200`；要验「超上界拒绝」就显式用 >200 断言异常/400 路径。
2. **参数绑定非法值走框架 400 翻译，业务错误才是信封 `Result.code≠0`**：HTTP 层断言先分清是哪类；枚举绑定非法值勿按业务信封断言。
3. **服务面/UseCase 签名以委派单「钉死事实」为准**：签名漂移先查钉死段，不猜旧签名；既有测试缺参补 null 属 TL 追认的兼容口径，新用例一律按新签名写。

## 后端测试装配（细则以 flex-test-assembly.md 为准，此处只列翻过车的）

4. **standalone MockMvc 切片断言成功信封必须显式注册 `StandardResponseAdvice`**（`mvcFor` 注册），否则 body 无信封包裹（REQ-213603 round1）；或改用 `@SpringBootTest` 全上下文。
5. **同模块混跑 H2 切片 + PG 集成必须 `reuseForks=false`**（Flex 全局方言 JVM 级静态污染）；装配照抄参考实现，不另起炉灶。
6. **Integration oracle 按「库中真实状态」推演**：祖先链组装/软删/租户过滤/种子幂等都会改行集，断言前先想清楚数据落库后应查到什么（REQ-213603 round2 两处 oracle 判用例错）。

## 前端交互事实（antd v5 / Testing Library）

7. **下拉交互目标是 `.ant-select-*`**：点击/展开/选中发到容器内 `.ant-select-selector` / `.ant-select-item-option`，勿对裸 role=combobox 节点直接操作（REQ-213603 round4，diagnostic 95% 用例错）。
8. **pickOption 限定在下拉浮层容器内**：「启用」「停用」等文案全局多命中，用 `within(<下拉浮层>)` 限定再按文本匹配（REQ-213603 round5）。
9. **a11y 树与 DOM 存在镜像差**：`getByRole` 找不到先看渲染树实际节点结构（列表项可能多包一层），不硬凑 role（REQ-213603 轮 5~7 交互三迭代）。
10. **选项/按钮文案常带后缀或图标**：精确全文断言前先看范本匹配方式，优先正则/宽容匹配（REQ-213603 轮 5~7）。
11. **交互范式照抄委派单钉死的范本路径**：筛选区展开/折叠、重置语义以钉死事实指定的既有页面形态为准，不自由发挥。

## 类型与断言

12. **含 `await` 交互的辅助函数显式标注返回类型**，防编译器自引用推断 TS7022（REQ-213603 round3）。
13. **trap 断言（负例消息）用 contains/正则**，勿整串精确匹配拼接（REQ-214653 round3）。
14. **mock/i18n 文案按委派单宽出口径匹配**（如「F-x 宽容匹配，红则 TL 裁决」），文案改动不破测。

## 环境与 flake（tester 自验与 TL 判定同口径）

15. **前端重 jsdom 文件并行会崩 Tinypool worker / 超时 flake**：先 `vitest run <file> --no-file-parallelism` 串行复跑判别，串行过 ≠ 回归，不进打回循环。
16. **跑后端 IT 前先 `docker version`** 确认 daemon 活着；日志见 `Could not find a valid Docker environment` = 全跳过 ≠ 验证（`Tests run: N, Skipped: 0` 才算）。
17. **先跑完后端再跑前端**，避免 CPU 争抢放大超时假失败。
