# Git 工作流

> 所有 AI 工具执行 Git 操作时必须遵守。

## 分支模型

| 分支 | 用途 |
|---|---|
| `main` | 受保护，始终可发布；只接受合并，不直接推送 |
| `feat/<issue>-<简述>` | 功能开发 |
| `fix/<issue>-<简述>` | 缺陷修复 |

## 提交信息规范（Conventional Commits）

```text
<type>(<scope>): <subject>

[可选正文：动机与影响]
```

- type 限定：`feat` / `fix` / `docs` / `refactor` / `test` / `chore` / `perf`
- subject 用中文或英文祈使句，不超过 50 字，结尾不加句号
- 示例：`feat(auth): 增加手机号验证码登录`

## 提交粒度

- 一次提交只做一件事；生成代码与格式化调整分开提交。
- 禁止提交与本次任务无关的文件。

## 代码评审（CR/PR）

- PR 描述包含：改动目的、影响范围、自测方式。
- AI 生成的改动必须在 PR 描述中注明。
- CR 未通过前不得合并。

## 禁止事项

- ❌ 强推（force push）到共享分支
- ❌ 提交密钥、证书、`.env` 等敏感文件
- ❌ rebase / reset 已推送他人的提交
- ❌ 提交未联动同步的 AI 配置改动（修改 `.ai/` 后必须先运行 `node .ai/sync.mjs`，见 `.ai/rules/ai-guidelines.md` 的「AI 配置联动更新」）
