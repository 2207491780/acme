<!-- ⚠️ 本文件由 .ai/sync.mjs 自动生成（服务对象：OpenAI Codex、智谱 ZCode 及所有遵循 AGENTS.md 标准的工具），请勿直接修改。
     所有 AI 配置唯一事实源：.ai/ 。修改源文件后运行 node .ai/sync.mjs 重新生成。 -->

# ACME 项目规范（入口）

本仓库所有 AI 规范统一维护于 `.ai/`。**开始任何任务前，必须先完整阅读 `.ai/RULES.md`** —— 它是公共规范（AI 协作准则、编码规范、Git 工作流、项目上下文）的唯一完整副本。

## 铁律（节选，完整版见 .ai/RULES.md）

1. 所有 AI 配置的唯一事实源是 `.ai/`，禁止直接修改任何工具目录下的派生文件。
2. 修改 `.ai/` 后必须立即运行 `node .ai/sync.mjs` 派生到所有工具，并通过 `node .ai/sync.mjs --check` 校验，否则提交会被 pre-commit 钩子与 CI 拒绝。
3. 未读 `.ai/RULES.md` 前，不得开始任何编码任务。
4. 凡修改 AI-Coding 配置（规范 / 分层规则 / 公共池 / 工具配置 / 钩子 / CI）：先读 `.ai-memory/playbooks/ai-config.md`，按其五步流程执行。
