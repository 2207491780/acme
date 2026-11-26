# .zcode/ — 智谱 ZCode（工作区作用域）

- 项目规范入口：根目录 `AGENTS.md` 引用存根（ZCode 自动加载；规范源 `.ai/rules/`）。
- **agents/ 与 commands/ 是公共池派生的引用存根**：`agents/*.md`、`commands/*.md` 由 `node .ai/sync.mjs` 直接从 `.ai/agents/<蜂群名>/` 蜂群公共池（现含 `ai-coding-dev/` 与 `code-review/` 双蜂群）与 `.ai/commands/` 派生到 `.zcode/`，frontmatter 原样保留供 ZCode 注册，正文只含指向唯一源的指针（执行时自行读取源文件）；不再有手写镜像源，新增角色/命令自动纳入派生。
- `.zcode/` 整体 git 忽略、本机生效；修改 `.ai/agents/`、`.ai/commands/` 公共池源后运行 `node .ai/sync.mjs` 刷新。
- **localOnly 口径**：本目录不入库——全新检出/CI 无此目录，`--check` 对缺失项跳过不判错；一旦存在则必须与 `.ai/` 一致（防漂移）。教训见 `.ai-memory/decisions/2026-11-14-20-zcode-stub-local-only-check.md`。
- 其余 ZCode 数据（会话态、工作流草稿等）ZCode 自管理，勿放入 `.ai/`。
