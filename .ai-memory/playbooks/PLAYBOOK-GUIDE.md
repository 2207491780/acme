# .ai-memory/playbooks/ — 操作手册源

存放供 AI Coding 工具**按需读取**的操作手册（playbook）。与规则不同：手册不拼接进 `RULES.md`，只在任务相关时被读取，避免占用每次会话的上下文。

| 手册 | 用途 | 触发方式 |
|---|---|---|
| [ai-config.md](ai-config.md) | 修改本仓库任何 AI-Coding 配置的标准流程 | 规则条款（`.ai/rules/ai-guidelines.md`「AI 配置联动更新（强制）」）；Claude Code / Gemini 可直接调用 `ai-config-editor` 子代理或 `ai-config` 技能 |
| [flex-test-assembly.md](flex-test-assembly.md) | 后端新增服务/仓储与集成测试的装配口径（MyBatis-Flex） | 分层规则（`.ai/scopes/workspace/apps/backend/AGENTS.md`，就近生效） |
| [naming.md](naming.md) | 文件/文件夹/包/标识符的统一命名格式（含豁免清单与大小写红线） | 规则条款（`.ai/rules/coding-standards.md`「语言与格式」） |
| [parallel-dev.md](parallel-dev.md) | AI 开发工作树标准流程与约束（所有产生提交的开发默认适用） | 默认适用：规则条款（`.ai/rules/git-workflow.md`「分支模型」），无需用户提示 |
| [multi-agent.md](multi-agent.md) | 契约驱动多代理开发流程（Team Leader 宪法：四阶段/门禁/打回循环/并发规则/例外条款） | 引擎开关 `engine.teamLeader`（值注入入口存根触发段）+ `.ai/skills/team-leader/` 技能 + `/req` 命令 |
| [conversational-cd.md](conversational-cd.md) | 会话内部署/回滚：触发 GitHub CD → 轮询 → 服务器核验 | 规则条款（`.ai/rules/project.md`「命令与门禁」部署行） |

## 说明

- 手册是**源文件**，仓库内引用（不派生）；修改直接编辑本目录。
- 新增手册后，在 `.ai/rules/` 对应规则中加入触发条款，让所有工具都知道它的存在。
