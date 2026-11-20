# 2026-11-21-01 tester 往返周期优化（分级自验权 + 事实钉死 + 会话续用）

> 部分取代 [2026-11-14-19](2026-11-14-19-multi-agent-contract-workflow.md)：其第 3 条「tester 无 Bash（只写不跑）」的「不跑」部分由本记录的分级自验权取代；tester 禁读实现（全盲）与 TL 全量门禁权威两项独立性核心**保留不动**。

## 背景

用户观察 tester 在多代理流程中占用时间最长。实测证据：

- REQ-20261120-213603 门禁 7 轮，**5 轮（round1~5）全在修 tester 用例**，实现代码零缺陷；REQ-20261120-214653 三个打回轮全为用例修复。两需求合计 8 个 tester 打回轮，无一例规格理解错误，**全是环境事实盲猜**（PageQuery MAX_SIZE=200、standalone 切片须注册 StandardResponseAdvice、antd v5 `.ant-select-selector` 层级、a11y 镜像节点、选项文案后缀、pickOption 容器限定）。
- ADR 2026-11-14-19 当年已预判「全盲写用例必翻车」而配 flex-test-assembly（后端装配 8 条）——但坑面远超后端装配，前端交互与框架上界无卡可查。
- tester「只写不跑」使反馈走最贵路径（TL 全量门禁 → diagnostic → delta 单 → 冷启动重写），前端单文件秒级可验的事变成一轮完整周期；每次冷启动重读 40~60KB 输入；单次交付 31 例横跨双端四栈，任一端失败拖全部回炉；打回轮之间全量门禁复跑（后端 mvn test 实测 30~55 分钟）且并行 flake（Tinypool）白耗轮次。

## 决定（六项，载体随列）

1. **分级自验权**：tester 增配 Bash，仅限白名单对**本轮交付用例**定向自验（前端单文件 `vitest run --no-file-parallelism`；后端单模块单类 `mvn -pl -Dtest` 或 `test-compile`）；全量门禁仍由 TL 统一执行。载体：`.ai/agents/tester.md`【自验权】+ `.ai/tools/zcode/agents/tester.md`（ZCode 存根源）+ `multi-agent.md` 硬红线 3 修订。
2. **事实钉死前置**：委派单模板新增「钉死事实」段（校验上界/信封装配/集成点签名/UI 交互范式）；tester 输入清单挂载新手册 `tester-pitfalls.md`（历轮踩坑收编）。载体：task-brief 模板 + tester.md + 新 playbook。
3. **打回轮会话续用**：delta 打回单优先 SendMessage 续原 worker 会话，仅设计性重写或会话不可恢复时新开。载体：multi-agent.md 打回循环 + 执行卡。
4. **门禁定向复跑 + flake 前置判别**：打回轮只跑受影响模块/文件，全量只留终验与合并后验证；jsdom 并行崩溃先串行复跑判别，不进打回循环。载体：multi-agent.md 门禁表注。
5. **大契约拆单**：跨端或预估用例 >15 时 tester 拆 backend/frontend 两单同轮并行，映射由 TL 合并回填。载体：multi-agent.md 委派纪律。
6. **交付自检清单**：映射齐/命名/teardown/上界信封口径/交互路径复用范本，写死 tester.md。

预期：213603 型需求 7 轮 → 1~2 轮，tester 相关墙钟时间降 60~70%（估）。

## 被否的选项

- **换更快模型跑 tester**：收益远小于消灭返工轮，且承担质量风险——否。
- **允许 tester 读实现代码消灭盲猜**：破坏 ADR 2026-11-14-19 保留的独立性核心；「钉死事实 + 坑卡」以不污染判定义务的方式供给同类信息——否。
- **tester 交付自跑全量门禁**：后端全量 30~55 分钟且与三路并行争抢 CPU（先端后端口径冲突）；全量属 TL 门禁职责——否。
- **维持打回轮新 spawn 现状**：每轮冷启动重读 40~60KB，续会话上下文无损且更省——否。
- **pitfalls 卡写入 `.ai/rules/` 全局规范**：RULES 预算紧张且该卡只服务 tester 单角色；playbook + tester 输入清单 + 委派单模板三处触发已足够，不入全局规范——否。
