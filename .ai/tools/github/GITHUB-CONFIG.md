# .github/ — GitHub 配置（含 Copilot 指令与 CI）

- `copilot-instructions.md`：Copilot Chat / Agent 模式的项目级指令，为薄存根（引用 `.ai/RULES.md`）。存根极小，不受 Copilot 指令文件长度限制困扰。
- 按路径生效的指令：`.ai/tools/github/instructions/*.instructions.md` → 本目录 `instructions/`。
- `workflows/ci.yml`、`workflows/cd.yml`：门禁与部署流水线，源在 `.ai/ci/`（GitHub 要求工作流必须位于本目录，故采用派生方式）；AI 配置联动校验由 ci.yml 的 ai-config 作业承担。
