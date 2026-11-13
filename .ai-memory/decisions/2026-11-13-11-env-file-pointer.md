# 简化为单一指针：environment.file 指向当前运行环境 yml（取代 09/10 的键设计）

> 取代 `.ai-memory/decisions/2026-11-13-09-env-debug-target-config.md` 与 `2026-11-13-10-env-debug-backend-semantics.md` 的键设计；
> 位置选择、引擎只校验不消费、规则联动等架构决定继续有效。语义（本地调试时后端连接的运行环境）沿用 10。

## 背景

用户反馈（2026-11-13）：`cloudEnv` 间接引用 `.ai/env/dev.yaml` 不够见名知意（"dev" 看不出是云端环境）；
要求去掉 `localDebug`/`cloudEnv` 两键，只保留「配置当前运行环境 yml」的单一选项。

## 决定

1. **单一指针**：`environment.file: cloud-dev.yaml`（当前值），取值为 `.ai/env/` 下的文件名；
   引擎校验 `^[a-z0-9-]+\.yaml$`（缺省 `local.yaml`），不查文件存在性（测试 fixture 为最小仓库，引擎不消费文件内容）。
2. **环境文件重命名（见名知意）**：`dev/test/prod.yaml` → `cloud-dev/cloud-test/cloud-prod.yaml`，新增 `local.yaml`；
   文件内 `env` 字段保持 dev/test/prod/local，与 k8s overlays、CD 环境名对齐不变。ENV-GUIDE/ENV-STACK/deploy.sh 引用同步。
3. **local/cloud 语义移入所指文件**：配置只声明「当前是哪个环境」，环境是什么（本地中间件/云端 k3s）由 yml 的 description/notes 表达。
4. **回归**：environment 用例改为 file 指针口径（3 正例 + 7 非法拒绝，含旧键 localDebug 拒绝）。

## 被否的选项

- **保留 localDebug/cloudEnv 两键**：否决——用户明确要求去掉；间接引用（dev→文件）不如直接文件名见名知意。
- **引擎校验所指文件存在**：否决——fixture 为最小仓库（无 .ai/env/），存在性校验会破坏全部既有配置用例；引擎姿态是只校验 schema。
- **env 字段一并改名 cloud-dev**：否决——k8s overlays、CD Environments、deploy.sh 参数均用 dev/test/prod，改字段会破坏部署契约对齐。
- **保留 dev/test/prod.yaml 另建调试文件**：否决（用户选定重命名方案）——两套命名并存割裂事实源。