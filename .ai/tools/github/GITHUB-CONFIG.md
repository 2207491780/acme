# .github/ — GitHub 配置（含 Copilot 指令与 CI）

- `copilot-instructions.md`：Copilot Chat / Agent 模式的项目级指令，为薄存根（引用 `.ai/RULES.md`）。存根极小，不受 Copilot 指令文件长度限制困扰。
- 按路径生效的指令：`.ai/tools/github/instructions/*.instructions.md` → 本目录 `instructions/`。
- `workflows/ai-rules-sync.yml`：联动校验 CI，源在 `.ai/ci/ai-rules-sync.yml`（GitHub 要求工作流必须位于本目录，故采用派生方式）。
