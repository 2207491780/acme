# AI 开发工作树手册（parallel-dev）

> 所有 AI 开发任务的默认工作方式（不依赖用户提示）；触发：任何会产生提交的开发任务。

## 何时使用

**所有开发任务（功能 / 修复 / 配置 / 文档，凡会产生提交）默认走工作树**，不需要用户提出「并行开发」；
主工作树只承载合并、推送与只读分析。理由：即使单会话，主工作树的 HEAD 也不能被长任务占住——
切分支会踩到其他会话、派生文件互写、`mvn`/构建跑到一半被 checkout 打断。
工作树 = 独立目录、共享 `.git`、钩子（`core.hooksPath`）与远端配置。

## 步骤

1. 开工第一步，在主工作树（通常停在 `acme-foundation`）执行：`node .ai/worktree.mjs add feat/<简述>`
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
