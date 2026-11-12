# 并行开发采用 worktree + 镜像一致性 CI 门禁 + 后端质量接线

## 背景

单工作树多 AI 并行互相踩踏（真实事故：mvn 全量测试跑一半被另一会话的分支切换打断）；
镜像依赖记忆维护已出现 9 文件偏差（决策×5、钩子内容与可执行位）；app-quality 在无前端
工程的镜像分支上必然红灯；后端工程无任何 CI 门禁。

## 决定

1. **并行开发 = git worktree**：`.ai/worktree.mjs` 管理（add/remove/list，落 `.worktrees/`），
   流程与约束落 `playbooks/parallel-dev.md`，规则在「分支模型」挂触发条款；同一分支禁止双工作树检出，
   长任务必须在工作树内跑。
2. **镜像一致性进 CI**：`mirror-sync` 作业（仅两条策略分支）校验 AI 路径（与 pre-commit 暂存清单同口径）
   在 `acme-foundation` ↔ `ai-coding-support` 逐字节一致，差异即失败并提示对齐。
3. **重作业加存在性守卫**：app-quality / backend-quality 增加 `hashFiles(...pom.xml|pnpm-lock.yaml) != ''`，
   无对应工程的分支自动跳过。
4. **后端门禁接线**：`backend-quality`（Java 25 + `mvn -B test`，限 main 直推与 PR）；本地全量
   38 过 / 7 跳过（无 Docker），CI runner 有 Docker，PG 集成测试经 testcontainers 真实执行。

## 被否的选项

- **锁文件禁止并行**：代价远超收益，worktree 已实现目录级隔离。
- **镜像自动 cherry-pick 机器人**：冲突消解与提交意图不可自动判定，保留人工 cherry-pick + CI 差异兜底。
- **CI 常驻 postgres service**：testcontainers 已按测试实际依赖动态起容器，常驻属资源浪费。
