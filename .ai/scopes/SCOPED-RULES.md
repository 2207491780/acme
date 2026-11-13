# .ai/scopes/ — 分层 AGENTS.md 源

遵循 AGENTS.md 开放标准的分层实践：仓库任意目录可拥有自己的 `AGENTS.md`，兼容的工具会按「从宽到具体」的优先级加载离当前工作目录最近的指令。根部规范（`.ai/rules/` 派生出的根 `AGENTS.md`）始终生效，分层文件只做**补充与覆盖**。

## 用法

- 源放本目录，**路径 = 仓库内相对路径**：

  ```text
  .ai/scopes/packages/AGENTS.md        → packages/AGENTS.md
  .ai/scopes/packages/client/AGENTS.md → packages/client/AGENTS.md
  ```

- 派生时引擎自动在同级生成 `CLAUDE.md`：默认为 `@AGENTS.md` 导入存根（引用同目录规范），`--links` 模式下为符号链接；Claude Code 同样就近生效。
- `node .ai/sync.mjs` 派生；`node .ai/sync.mjs --check` 校验。
- 分层内容建议短小聚焦（目录职责、局部约定），公共规范仍放 `.ai/rules/`。
- ⚠️ **路径即仓库路径**：新增源前确认对应仓库路径下没有同名的既有非派生文件——派生会静默覆盖同名文件（引擎只拦截「多个源写同一目标」，拦不住「源覆盖既有非派生文件」）。
- ⚠️ 孤儿检测覆盖 scopes 派生目标的同级目录（仅报带派生标记的残留）；整目录删除源后，对应路径的 `AGENTS.md` / `CLAUDE.md` 若无派生标记仍需手动删除。
