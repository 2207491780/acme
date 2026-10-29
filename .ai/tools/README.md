# .ai/tools/ — 各工具专属配置源

每个子目录对应一个工具，**目录结构镜像目标工具目录**，由 `.ai/sync.mjs` 整体派生。
源 → 目标映射见 [../README.md](../README.md) 的「派生一览」（唯一权威表）。

## 用法

给某个工具加私有配置（如 Cursor 按 glob 生效的规则、Claude 的 `settings.json`、Gemini 的 TOML 命令、Copilot 按路径生效的 instructions），写在对应子目录的镜像路径下，然后运行：

```bash
node .ai/sync.mjs
```

## 说明

- 子目录的 `README.md` 会派生为工具目录内的说明文件，「⚙️ 生成产物」标头由引擎自动前置，**无需手写**。
- `.md` 派生时尾部自动加「派生自」标注；`.yml/.toml` 头部加 `#` 溯源注释；其他格式原样复制。
- 跨工具共享的内容不放这里——放 `.ai/agents|skills|commands/` 公共池或 `.ai/rules/`。
