# 前端 web-admin 按 vben v5 官方指引裁剪应用

## 背景

`workspace/apps/frontend/web-admin` 是 vben v5 脚手架单仓，脚手架自带 5 个 UI 版本应用
（`web-antd` / `web-antdv-next` / `web-ele` / `web-naive` / `web-tdesign`）+ `backend-mock`。
本仓库只需要其中一种 UI 版本，多应用会拖慢安装、构建，并在 catalog 里留下三套组件库依赖。
vben v5 官方文档 `docs/src/guide/introduction/thin.md`「精简版本」给出了官方裁剪步骤：
删除不需要的应用目录 → `pnpm install` → 调整根 `package.json` 的 `scripts`。

## 决定

保留 `web-naive` 与 `backend-mock`，裁掉 `web-antd`、`web-antdv-next`、`web-ele`、`web-tdesign`；
裁剪范围严格对齐官方「应用精简」章节：

1. 删除 4 个 `apps/*` 目录；
2. 根 `package.json` 移除对应的 `build:*` / `dev:*` 脚本；
3. `vben-admin.code-workspace` 移除对应 folder；
4. `pnpm-workspace.yaml` catalog 移除随应用一起失效的 `ant-design-vue`、`element-plus`、
   `tdesign-vue-next`、`unplugin-element-plus`（`antdv-next` 被 `playground` 与 `packages/styles`
   使用，保留）；
5. `pnpm install --lockfile-only` 重新生成 `pnpm-lock.yaml`（CI 用 `--frozen-lockfile`）；
6. 清理 `.changeset/fresh-tiptap-model.md` 中对已删包的引用，否则 `changeset version` 会失败；
7. 更新 `.ai/scopes/.../web-admin/AGENTS.md` 的应用清单并运行 `node .ai/sync.mjs` 派生。

## 被否的选项

- **同时删掉 `playground` / `docs`**：官方文档把它们列为可选项（演示代码精简、文档精简），
  但不在本次任务范围内，且 `playground` 承载 e2e 与组件演示，删除会损失验证手段 → 不删。
- **改 `.github/workflows/deploy.yml` 与 `.vscode/launch.json` 里指向 `apps/web-antd`、`apps/web-ele`
  的路径**：两者按 `web-admin/AGENTS.md` 属于上游遗留（`GitHub` 只识别仓库根 `.github/`），
  且 `.ai/rules/ai-guidelines.md` 禁止未经要求改 CI 配置 → 保留并作为遗留事项上报，由人决定。
- **手工编辑 `pnpm-lock.yaml` 删除 importer**：锁文件手工改易出错，改用 pnpm 生成 → 不手工改。
- **保留无用 catalog 条目**：虽然不影响安装，但与「裁剪」目标冲突，且会让后续误以为仍可引用
  这些组件库 → 移除。
