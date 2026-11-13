# .zcode/ — 智谱 ZCode（工作区作用域）

- 项目规范入口：根目录 `AGENTS.md` 引用存根（ZCode 自动加载；规范源 `.ai/rules/`）。
- **agents/ 与 commands/ 是引用存根**：`agents/*.md`、`commands/req.md` 由 `.ai/tools/zcode/` 派生，正文只含一行指针，完整角色/命令定义唯一源在 `.ai/agents/` 公共池与 `.ai/commands/`（子代理与命令执行时自行读取源文件）；frontmatter 仅为 ZCode 注册所需的最小元数据。
- `.zcode/` 整体 git 忽略、本机生效；修改存根源后运行 `node .ai/sync.mjs` 刷新。
- 其余 ZCode 数据（会话态、工作流草稿等）ZCode 自管理，勿放入 `.ai/`。
