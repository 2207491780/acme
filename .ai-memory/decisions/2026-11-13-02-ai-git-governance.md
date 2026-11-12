# AI Git 行为治理：授权分级 + 本地钩子强制 + CI 门禁前移

## 背景

仓库进入多人多 AI 协作阶段：AI 的高危 Git 操作（直推 main、强推、删共享分支）此前只靠规范条文约束，
无本地强制；提交信息规范全靠自觉；功能分支 push 完全没有 CI 门禁；AI 产出无结构性留痕；
hooks 启用靠手工记忆（clone 后极易遗漏）。`ai-coding-support` 双分支策略仅为会话惯例。

## 决定

1. **规范层**：`git-workflow.md` 新增「AI 操作授权分级」（自主 / 需指令 / 禁止三档，指令不外扩），
   push 前核对目标分支；提交 trailer `Assisted-by: <工具名>` 留痕（PR 描述注明保留，双保险）。
2. **钩子层**：新增 `commit-msg`（Conventional Commits 校验，不合规**拒绝**，merge/revert/fixup 豁免）与
   `pre-push`（禁直推/强推/删除 main，非快进拦截）；`enable.mjs` 一键启用并自检；配套 14 项沙箱回归测试。
3. **CI 层**：push 触发扩至全分支，ai-config 门禁前移（轻量）；app-quality 作业级 `if` 限 main 直推与 PR。
4. **平台层**：CI-GUIDE 新增「团队接入 checklist」（main 分支保护、PR 审核、成员 enable.mjs）。
5. `ai-coding-support` 写入分支模型表，成为成文规范。

## 被否的选项

- **commit-msg 仅警告**：规范已成文且本仓库提交几乎全经 AI 手，警告不可靠且会被忽视；拒绝执行成本低。
- **引入 gitleaks/commitlint 等外部工具**：违背零外部依赖原则（sync.mjs/测试均为 Node 内置）；
  敏感文件已有 .gitignore 分区 + 规则红线兜底。
- **app-quality 随全分支触发**：pnpm install+构建分钟级成本，会拖慢功能分支迭代；轻量门禁前移即可。
- **pre-push 层拦截删远端分支**：误伤正常分支清理；删已推送分支由「授权分级」管，钩子只拦 main。
