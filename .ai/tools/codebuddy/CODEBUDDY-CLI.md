# .codebuddy/ — 腾讯 CodeBuddy（本机作用域）

- 项目规范入口：根 `CODEBUDDY.md` 引用存根（CodeBuddy 自动加载；缺省时工具回退读 `AGENTS.md`）。
- `agents/` 与 `commands/` 由引擎从 `.ai/agents/`、`.ai/commands/` 公共池**全量拷贝派生**（frontmatter 与公共池一致，CodeBuddy 原生识别；五个子代理 + `/req` 命令）。
- `.codebuddy/` 整体 git 忽略、本机生效（localOnly，口径同 `.zcode/`）：`--check` 对缺失跳过不判错，存在则必须与 `.ai/` 一致（防漂移）；教训见 `.ai-memory/decisions/2026-11-14-20-zcode-stub-local-only-check.md`。
- 其余 CodeBuddy 数据（如 `teams/`）工具自管理，勿放入 `.ai/`。
