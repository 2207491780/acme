# .ai/tools/ — 各工具专属配置源

每个子目录对应一个工具，**目录结构镜像目标工具目录**，由 `.ai/sync.mjs` 整体派生。
源 → 目标映射见 [../ARCHITECTURE.md](../ARCHITECTURE.md) 的「派生一览」（唯一权威表）。

## 用法

给某个工具加私有配置（如 Cursor 按 glob 生效的规则、Claude 的 `settings.json`、Gemini 的 TOML 命令、Copilot 按路径生效的 instructions），写在对应子目录的镜像路径下，然后运行：

```bash
node .ai/sync.mjs
```

## 目录索引

| 子目录 | 工具 | 派生目标 | 说明 |
|---|---|---|---|
| `claude/` | Claude Code | `.claude/` | 目录说明 + 私有配置 |
| `cursor/` | Cursor | `.cursor/` | 目录说明 + 私有规则等；agents/skills/commands 由公共池派生为引用存根（ADR 2026-11-18-01） |
| `gemini/` | Gemini CLI | `.gemini/` | 目录说明 + 私有配置（命令为手工 TOML） |
| `antigravity/` | Google Antigravity | `.agents/` | 目录说明 + `skills.json`（官方 JSON 配置直引 `.ai/skills`，ADR 2026-11-27-05） |
| `trae/` | 字节 Trae | `.trae/` | 目录说明 + 私有配置 |
| `qoder/` | 阿里 Qoder | `.qoder/` | 目录说明 + 私有配置 |
| `github/` | GitHub Copilot | `.github/` | 目录说明 + instructions |
| `zcode/` | 智谱 ZCode | `.zcode/` | localOnly（git 忽略、不入库）：agents/commands 引用存根 |
| `codebuddy/` | 腾讯 CodeBuddy | `.codebuddy/` | localOnly（git 忽略、不入库）：目录说明；agents/skills/commands 由公共池派生为引用存根（frontmatter 保留、正文指针，官方原生识别，`mirrorPools`，ADR 2026-11-17-03） |

## 说明

- 子目录的说明文件（如 `CLAUDE-CODE.md`）会派生为工具目录内同名文件，「⚙️ 生成产物」标头由引擎自动前置，**无需手写**。
- `.md` 派生时尾部自动加「派生自」标注；`.yml/.toml` 头部加 `#` 溯源注释；其他格式原样复制。
- 跨工具共享的内容不放这里——放 `.ai/agents|skills|commands/` 公共池或 `.ai/rules/`。
