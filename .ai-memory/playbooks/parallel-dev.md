# AI 开发工作树手册（parallel-dev）

> 所有 AI 开发任务的默认工作方式（不依赖用户提示）；触发：任何会产生提交的开发任务。

## 何时使用

**所有开发任务（功能 / 修复 / 配置 / 文档，凡会产生提交）默认走工作树**，不需要用户提出「并行开发」；
主工作树只承载合并、推送与只读分析。理由：即使单会话，主工作树的 HEAD 也不能被长任务占住——
切分支会踩到其他会话、派生文件互写、`mvn`/构建跑到一半被 checkout 打断。
工作树 = 独立目录、共享 `.git`、钩子（`core.hooksPath`）与远端配置。

## 步骤

1. 开工第一步，在主工作树（通常停在 `acme-foundation`）执行：`node .ai/worktree.mjs add feat/<简述>`
   （add 内置**自动 GC**：已随主线 push 到远端且无未提交修改的干净工作树被自动回收——2026-11-20
   上线；分支 tip 未 push 的一律保留（宁可不清，不可误删）；镜像常驻检出 `ai-coding-support`
   与主工作树永不触碰。手动清理/预检：`node .ai/worktree.mjs gc [--dry-run]`；加 `--branches`
   追加回收**已随主线 push 且未检出**的本地分支（保护分支 acme-foundation/main/ai-coding-support
   永不触碰；未推送分支删除属授权自主级）。注意：GC 递归删除
   带大量依赖产物（node_modules）的工作树在 Windows 上可能耗时数分钟，属正常；孤儿目录（曾被
   硬删注册的残留）不在 GC 管辖，需人工裁决后删除）
2. AI 进入 `.worktrees/<分支>/` 工作：根与分层 AGENTS.md 随检出自带生效；钩子经共享配置自动生效；
   `.ai-tmp/` 按工作树隔离；**在任务结束前不要切回主工作树的分支**。
3. 需要跑前端时在工作树内 `cd workspace/apps/frontend && pnpm install`（pnpm 共享 store，安装快）；
   后端在 `acme-cloud/` 下 `mvn test`（本地 `.m2` 共享）。
4. **合流三步（本地开发 → 合并 → 推送）**：① 回主工作树（`acme-foundation`）合并该分支（优先快进，
   无法快进用合并提交说明理由）；② 合并后先验证（`node .ai/sync.mjs --check` / 受影响端测试 / 构建）；
   ③ 推送前把 AI 配置对齐到 `ai-coding-support`（cherry-pick 或按路径 checkout 均可），并用**同一条命令**
   推两分支：`git push origin acme-foundation ai-coding-support`（双远端即同时命中 Gitee+GitHub）。
   pre-push 会用 `.ai/hooks/mirror-check.mjs` 预检两分支 AI 路径逐字节一致，不一致直接拒推；
   分先后推会让 mirror-sync 在窗口期内误报红灯（2026-11-13 实测），真误报则对齐后 re-run 该作业。
   功能分支**默认不推远端**（pre-push 拒绝 `feat/*`、`fix/*`；确需远端存档用 `ACME_ALLOW_BRANCH_PUSH=1`
   显式豁免，删除远端任务分支不受限）；收尾 `node .ai/worktree.mjs remove feat/<简述>`，
   删分支按「AI 操作授权分级」取得指令。

## 约束

- 只读分析（不产生提交）可留在主工作树；一旦需要改文件，先转工作树再动手。
- 同一分支不得同时检出于两个工作树（git 本身会拒绝）。
- 两个 AI 改同一文件 = 合并冲突：任务切分按目录/模块避让；**`.ai/` 与 `.ai-memory/` 的修改同一时刻
  只允许一个会话持有**（pre-commit 的 AI 配置原子提交契约），其余会话只读。
- 长任务（构建 / 全量测试 / 长时间验证）必须在工作树里跑，不得占主工作树。
- 每个工作树有独立 `.ai-tmp/`；确需共享中间产物时放主工作树并在汇报中说明路径。
- `git commit` 的钩子（含 commit-msg / pre-commit）在每个工作树都生效，不要在工作树里 `--no-verify`。

## 多人协作集成纪律（2026-11-20 补强）

> 本地多会话并行规则不变（一会话一需求一 worktree 一分支）；本节定义**分支被多人共享**后的集成方式。

- **共享判定**：分支已推送远端、或第二人检出/协作＝共享分支。共享后的铁律：**禁止 rebase 共享分支**
  （改写他人基于的提交＝授权分级禁止档）；集成主线一律 `merge`。
- **个人分支（未推送/未共享）**：维持现行——`git fetch` 后 rebase 到 `origin/acme-foundation`，
  逐提交解冲突，保持线性历史供主线优先快进；rebase 冲突堆积严重时允许降级为 merge 集成
  （主线收合并提交需说明理由）。
- **共享分支集成三步**：① `git fetch` → `git merge origin/acme-foundation`（把主线 merge 进分支）；
  ② 本地解冲突（专项口径见下）→ **重跑门禁**（`node .ai/sync.mjs --check`＋受影响端测试/构建）；
  ③ push 共享分支需 `ACME_ALLOW_BRANCH_PUSH=1` 豁免（pre-push 拒 `feat/*`、`fix/*`），推送后再回
  主线合并（无法快进用合并提交说明理由）。
- **冲突解的专项口径**：
  - `.ai/` 派生文件（`.claude/`、`.cursor/` 等）冲突**不手解**——解 `.ai/` 源后跑 `node .ai/sync.mjs`
    重新生成，派生侧以生成结果为准；
  - `contracts/` 冲突以契约权威裁决（TL 更新唯一现状；同名 REQ-ID 禁止两边各留一份）；
  - 双方改同一业务文件：按模块归属裁决或提请需求方澄清，禁止各留一半语义。
- **跨人取号**：需求号/迁移号以 `contracts/` 入库记录为准，取号前先 `git fetch`（契约已入库，
  远端可见他人占用，防撞不再依赖同机文件系统）。
