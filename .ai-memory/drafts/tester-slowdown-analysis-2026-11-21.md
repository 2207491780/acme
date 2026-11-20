# tester 执行缓慢：根因分析与提速方案（草稿，2026-11-21）

> 现象（用户观察）：多代理流程中 tester 执行占用时间最长。
> 证据基准：contracts/REQ-20261120-213603.md（round=7）与 contracts/REQ-20261120-214653.md 的门禁台账/委派历史。
> 落地前需走 ai-config 五步流程（涉及 `.ai/agents/tester.md`、task-brief 模板、multi-agent.md）。

## 一、证据台账

### REQ-20261120-213603（菜单筛选）
- tester 交付 4 文件共 **31 例**：Surface(M stands for MockMvc 切片) 10＋Integration(Testcontainers PG) 7＋Page(Testing Library) 7＋Mock(msw) 7，横跨 Java/TSX 两栈四种装配形态。
- 门禁 7 轮，**5 轮（round 1~5）全在修 tester 用例**；台账明载「轮 5~7 为 tester 用例交互缺陷三迭代（antd v5 selector 层级/a11y 镜像节点/选项文案后缀），**实现代码零缺陷**」。
- 逐轮归因：①standalone 切片未注册 StandardResponseAdvice；②Integration oracle 两处（判用例错）；③TS7022 类型标注；④下拉交互目标应 .ant-select-selector（diagnostic 95% 用例错）；⑤pickOption 未限定容器内。
- 另有一轮 usersPage 失败为**并行 flake**（Tinypool 崩溃，flex-test-assembly 已有串行复跑口径），白耗一轮。

### REQ-20261120-214653（权限筛选）
- tester 交付 backend 12＋web 8＝20 例；3 个打回轮全在修 tester 用例（AC-13 夹具、服务面类型口径、9 处 size≤200＋trap 断言）。
- 典型盲猜实证：tester 写 `size=500` 撞框架 `PageQuery MAX_SIZE=200` 上界 → **6 Error**。tester 禁读实现、契约未钉死该上界，只能猜。

### 输入面实测（每次冷启动）
角色定义 1.6KB＋各端总览 16.7KB＋分层规范 4.3KB＋flex-test-assembly 5.6KB＋契约节＋范本文件（300~650 行）≈ **40~60KB 输入**，每轮打回全部重读。

## 二、根因（按时间占比排序）

- **RC1 结构性信息缺口 → 返工轮次集中（最大头）**：tester 对实现全盲是设计保留（ADR 2026-11-14-19），且当年已预判「全盲必翻车」而配了 flex-test-assembly（后端装配 8 条）——但坑面不止后端装配：框架校验上界、响应信封 advice 注册、antd v5 DOM/交互细节均无卡可查，只能盲猜。两份契约共 8 个 tester 打回轮，**无一例是规格理解错误，全是环境事实盲猜**。
- **RC2 无自验回路（反馈走了最贵路径）**：tester「只写不跑」，前端单文件 vitest 秒级可验的事，只能经「TL 门禁复跑→diagnostic 归因→delta 单→冷启动重写」的慢循环，1 分钟回路变成 1 轮完整周期。
- **RC3 打回轮冷启动重读**：每轮新 spawn 重读全部输入（RC 网络约 40~60KB），5~7 轮累计可观；ZCode 存根还要先读角色文件。未使用会话续用。
- **RC4 单次交付体量最大且跨双端**：tester 是唯一横跨前后端、四栈形态的工人，31 例单发写作时长天然最长，且任一端失败拖全部交付回炉。
- **RC5 门禁全量复跑放大周期**：打回轮之间 TL 重跑全量（后端 mvn test 实测 30~55 分钟＋前端全链），且并行 flake 未按既有口径前置判别，虚增轮次。

## 三、方案（保质量、缩短时间）

| # | 方案 | 治哪个根因 | 质量影响 | 落点 |
|---|---|---|---|---|
| P1 | **打回轮会话续用**：TL 对同轮修复用 SendMessage 续原 tester 会话，仅归因=设计性重写时新开 | RC3 | 无损 | multi-agent.md「打回循环」＋执行卡各加一句 |
| P2 | **事实钉死前置＋坑卡沉淀**：委派单模板加「钉死事实」段（上界/信封/路由/UI 交互范式=范本路径）；新建 `.ai-memory/playbooks/tester-pitfalls.md` 收编历轮踩坑，tester 输入清单挂载 | RC1 | ↑（盲猜→照抄） | task-brief-template.md＋tester.md＋新 playbook |
| P3 | **双端拆单并行**：backend/frontend 用例拆两个 tester 委派同轮并发，映射表由 TL 合并回填 | RC4 | 无损（wall time ≈ max(单端)而非 sum(31 例)） | multi-agent.md 阶段二 |
| P4 | **分级自验权**：tester 增配 Bash（白名单），允许跑自己本轮写的前端单文件 `vitest run <file> --no-file-parallelism`；后端可选 `mvn -pl <模块> -Dtest=<类> -Dsurefire.failIfNoSpecifiedTests=false`（有 Docker 时）；TL 全量门禁仍是权威终验 | RC2 | ↑↑（交互/类型类缺陷当场暴露） | tester.md tools＋硬红线 3 修订＋补 ADR（只增不改） |
| P5 | **门禁定向复跑＋flake 前置判别**：打回轮只跑受影响模块/文件；jsdom 并行崩溃按既有口径串行复跑判别、不进打回循环；全量只留终验与合并后验证 | RC5 | 无损 | multi-agent.md 门禁表注记 |
| P6 | **tester 交付自检清单**：映射齐/命名 should_*_when_*/teardown/上界与信封口径/范本交互路径核对，写死 tester.md | 全部 | ↑（减少 TL 收交付往返） | tester.md |

- 预期：213603 型需求 7 轮 → 预计 1~2 轮（P2+P4 直接命中 8 个打回轮的成因），tester 相关墙钟时间估计降 60~70%（估）。
- 优先级：P2、P4 先行（治本）→ P1、P5（治过程）→ P3、P6（增益）。
- 不建议动模型档位（inherit）提速——收益远小于消灭返工轮。
