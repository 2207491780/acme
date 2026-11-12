# Git 传输采用本地双远端直推，取代 Gitee 推送镜像

> 取代 `2026-11-13-07-ci-github-dual-hosting.md` 中「Gitee 配置仓库镜像管理推送镜像到 GitHub」的
> 传输机制；其平台决策（GitHub Actions 作 CI 执行器、SSH 触发部署、Secrets 体系）不变。

## 背景

07 号决策采用 Gitee 控制台的「仓库镜像管理」做 Gitee→GitHub 自动同步，需要用户在 Gitee
控制台配置并托管 GitHub PAT。用户改选**本地双远端**方案：Gitee 只作国内快速访问（fetch
日常源），GitHub 仅作 CI 执行器（push 触发 Actions）。

## 决定

- `origin` 保持 Gitee 为 fetch 源；push 配置**双 pushURL**（先 `set-url --add --push` 钉住
  Gitee，再 `--add` 追加 GitHub），一条 `git push origin <分支>` 同时命中两仓。
- 需推送的分支：`acme-foundation`（主线）与 `ai-coding-support`（AI 配置镜像，mirror-sync
  CI 依赖两分支在 GitHub 同在）；`feat/*` 按需。
- GitHub 仓默认分支建议设为 `acme-foundation`（cd.yml 手动触发入口在含该文件的分支上）。
- 认证：GitHub 侧 HTTPS+PAT 或 SSH 均可，由用户选择；凭证只进本机 credential manager /
  GitHub 账号设置，不入仓库。

## 被否的选项

- **Gitee 控制台推送镜像（07 号原机制）**：需在 Gitee 托管 GitHub PAT 且同步行为受平台
  限制（时机/范围不透明）；双远端直推语义明确、无第三方环节。
- **GitHub 作 fetch 主源**：国内拉取慢，违背「Gitee 只为国内快速访问」的分工定位。
- **两台独立 remote（`gitee` + `github`）分别 push**：易漏推一侧造成 CI 与源仓漂移；
  双 pushURL 保证单命令原子双推。
