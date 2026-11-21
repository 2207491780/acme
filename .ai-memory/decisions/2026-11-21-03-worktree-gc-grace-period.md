# worktree 自动 GC 新建宽限期防线

日期：2026-11-21
关联：`contracts/REQ-20261121-200415.md`（THEME-3，事故受害 REQ）、`.ai/worktree.mjs`、`.ai-memory/playbooks/parallel-dev.md`

## 事故

2026-11-21 约 20:00，TL 执行 `node .ai/worktree.mjs add feat/req-20261121-200724-job-executor-sdk`（JOB-2 取号）时，`add` 内置自动 GC 把**正在使用**的 THEME-3 工作树（`.worktrees/feat-req-20261121-200415-theme-prefs-sync`，t3-frontend/t3-tester-fe 两个子代理在场作业）移除：`.git` 管理文件与跟踪文件被删，仅 node_modules 锁定残留。子代理 t3-frontend 按规范就地停止零改动上报，无工作丢失；分支 ref 完好，工作树当场重建恢复。

## 根因

GC 判据「分支 tip ⊆ origin/acme-foundation 且工作树无未提交修改」存在盲区：**契约单分支 REQ**（契约直接提交主线并随即推送、业务代码尚未动工）满足 tip==远端 tip + 零改动，与「合并推送后的陈旧工作树」特征完全同形。旧判据无法区分「刚取号待开发」与「已收口待清理」。

## 决定

1. `.ai/worktree.mjs` GC 增加新建宽限期：工作树年龄（`.git/worktrees/<name>` 管理目录创建时间）< `ACME_WORKTREE_GC_MIN_AGE_MS`（默认 6h）一律跳过；管理目录不可读按「足够老」走后续 dirty/pushed 判定。
2. `parallel-dev.md` GC 口径同步宽限期条款。
3. 恢复手法沉淀：误删工作树若分支 ref 完好 → 清残留目录 + `git worktree add <dir> <既有分支>`（不加 `-b`）原地重建，agent 无损续作。

## 影响

- 契约单分支/纯文档分支的工作树不再有「取号即被下一个 add 扫掉」的窗口；代价是已收口工作树最多滞留 6h 再被 GC（可 `gc` 手动提前）。
- TL 纪律：脚本输出禁止截断阅读（本次 GC 移除提示行被 `Select-Object -Last 4` 截掉，延长了定位时间）。
