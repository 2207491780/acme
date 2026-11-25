# 前端 vitest 判别 cwd 坑 + 负载 flake 新成员（2026-11-25，REQ-20261124-224523 delta-r2 终验实证）

## 坑：判别命令的 cwd 决定配置面（TL 门禁侧）

- 从 monorepo 根（`workspace/apps/frontend`）跑 `pnpm vitest run web/tests/...` 会用**错误的 vitest 配置面**：web 重 jsdom 页面用例全文件秒灭（tenantEnhance 14/14、tabs 18/18、crudPages 31/31 等，25-32ms/例），假象为「串行更红」。
- **正确口径**（与 tester 自验白名单一致）：`cd workspace/apps/frontend/web` 后 `pnpm exec vitest run tests/<file> --no-file-parallelism`。
- 判别反转教训：并行 9 红 → 错误 cwd 串行 85 红 → 正确 cwd 串行 88/89 绿。见到「串行比并行更红」先怀疑 cwd/配置面，不是回归。

## 已知负载敏感 flake 名单新增

- `REQ20261114120101-crudPages.test.tsx`（Menus 族）：红用例**逐轮轮换**（并行轮=create_modal+prefill_update、串行轮=icon_clear 25s 超时、空闲隔离=同一 create_modal 单测 22.6s 绿贴 25s 超时线）——隔离单测绿即披露放行，不进打回循环。
- 同批并行假红：filterPanel 2（串行绿，但单文件 150s、最重用例 97s）、tabs 1、nav 1、tenantEnhance 2、tenantInitTemplate 1（30s 超时）——全部正确 cwd 串行/隔离转绿。

## 其他可复用事实

- 双推实测 origin 同时指向 gitee+github 两个 pushurl（一条 `git push origin acme-foundation ai-coding-support` 四个 ref 全走）。
- worktree 回收前先 `taskkill /T /F /PID <vite PID>`（dev server 占目录锁）；worktree.mjs remove 会提示分支删除按授权分级留待指令。
- 亮色/暗色令牌解析值样本（走查比对用）：×标记 rect 暗 `#1d2127/#b6bcc6` ↔ 亮 `#ffffff/#4a5261`；minimap 底 暗=暗面板 ↔ 亮 `rgb(241,242,244)`。
- msw dev 默认启用（`VITE_ENABLE_MSW` 缺省 on），登录=任意凭据（admin）——前端走查无需后端/隧道。
