# .ai-memory/playbooks/ — 操作手册源

存放供 AI Coding 工具**按需读取**的操作手册（playbook）。与规则不同：手册不拼接进 `RULES.md`，只在任务相关时被读取，避免占用每次会话的上下文。

| 手册 | 用途 | 触发方式 |
|---|---|---|
| [ai-config.md](ai-config.md) | 修改本仓库任何 AI-Coding 配置的标准流程 | 规则条款（`.ai/rules/ai-guidelines.md`「AI 配置修改任务」）；Claude Code / iFlow / Gemini 可直接调用 `ai-config-editor` 子代理或 `ai-config` 技能 |
| [flex-test-assembly.md](flex-test-assembly.md) | 后端新增服务/仓储与集成测试的装配口径（MyBatis-Flex） | 分层规则（`.ai/scopes/workspace/apps/backend/AGENTS.md`，就近生效） |
| [naming.md](naming.md) | 文件/文件夹/包/标识符的统一命名格式（含豁免清单与大小写红线） | 规则条款（`.ai/rules/coding-standards.md`「语言与格式」） |

## 说明

- 手册是**源文件**，仓库内引用（不派生）；修改直接编辑本目录。
- 新增手册后，在 `.ai/rules/` 对应规则中加入触发条款，让所有工具都知道它的存在。
