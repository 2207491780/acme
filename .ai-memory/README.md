# .ai-memory/ — 知识与记忆落盘（独立于 AI 配置工程）

与 `.ai/`（AI-Coding 配置工程：规范、派生引擎、钩子、CI）**分离**：本目录存放**会话中沉淀的知识**，供人与 AI 按需读取，不参与派生。

| 层 | 位置 | 性质 |
|---|---|---|
| 定稿决策（为什么） | [decisions/](decisions/README.md) | ADR 三段式，**只增不改** |
| 操作手册（怎么做） | [playbooks/](playbooks/README.md) | 按需读取 |
| 工作记忆（草稿） | [drafts/](drafts/README.md) | 随时改写；`*.local.md` 个人草稿不入库 |

## 说明

- **约束**（是什么）不在这里——在 `.ai/rules/`（需拼接派生进所有工具入口，属配置工程）。
- **沉淀路径**：`drafts/` 中验证成熟的结论 → 提炼进 `decisions/`（理由）、`playbooks/`（步骤），或升级为 `.ai/rules/` 约束 → 删除草稿。
- 落盘强制条款见 `.ai/rules/ai-guidelines.md`「过程性内容落盘」。
- 各层格式详见各自 README。
