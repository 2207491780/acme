# frontend 目录规范

前端多项目根目录。**每个子目录是独立项目**，各有自己的 `package.json` 与构建链。

| 子目录 | 状态 | 说明 |
|---|---|---|
| `web-admin/` | ✅ 活跃 | 基于 vben v5 的中后台脚手架（Vue 3 + Vite + TS，pnpm 11 + turbo） |
| `data-visual/` | 占位 | 数据可视化，待建 |
| `flutter-app/` | 占位 | 移动端 Flutter，待建 |
| `wechat-mini/` | 占位 | 微信小程序，待建 |

## 约定

- **包管理器统一 pnpm**；在子项目目录内执行其 `package.json` 定义的脚本，不要在根目录假设有 workspace。
- 进入具体子项目前先读该目录的说明文件（如 `web-admin/AGENTS.md`）。
- ❌ 不在本目录及各子项目内新增 AI 工具配置（`CLAUDE.md`、`.cursor/` 等）——AI 规范由仓库根 `.ai/` 统一派生；本目录的规则即本文件。
- 占位目录（data-visual / flutter-app / wechat-mini）在技术选型确定前不创建脚手架代码。

<!-- 派生自 .ai/scopes/workspace/apps/frontend/AGENTS.md（统一管理于 .ai/），请修改源文件后运行 node .ai/sync.mjs -->
