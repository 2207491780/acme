# 评审委派单 {REVIEW-ID}-{role}

- **任务摘要**：{3-5 行：审什么 + 依据哪份事实清单 + 交付什么；子 Agent 靠此即可开工，不读任何流程文档}
- **评审号**：{REVIEW-YYYYMMDD-HHMMSS}（leader 评审开始时生成；临时脚本目录 `.ai-tmp/code-review/{REVIEW-ID}/`）
- **输入**：阶段 0 带 `file:line` 的事实清单节：{结构 / 契约 / 调用链 / 运行时 / 规范，按角色挑}；评审范围文档：{相关分层 AGENTS.md、FRONTEND.md/BACKEND.md/UI-SPEC.md}
- **边界**：只回传结论给 leader；禁读 {对端实现 / 清单外模块 / 范围外下游}；禁写仓库内任何文件；不产生 REQ、不写第二份 md、不写业务代码
- **脚本边界（适用 7 个工人角色）**：Write/Bash 只能把审核 `.mjs` 写到 `.ai-tmp/code-review/{REVIEW-ID}/`，遵守 SKILL「跨平台硬规范」六条，用完经 `run.mjs --cleanup` 删除；`verdict-arbitrator` 纯只读，无 Write/Bash
- **验证级别**：{L0 / L0+L1 / L0+L1+L2（认证/租户/事务/MQ 幂等顺序/双形态/DTO 序列化用 L2）}
- **交付物**：{带 `file:line` 的意见列表（文件:行 | 维度 | 问题 | 证据 | 建议 | origin | 归属）+ L1/L2 命令与输出摘录 + SKIP(原因)}
