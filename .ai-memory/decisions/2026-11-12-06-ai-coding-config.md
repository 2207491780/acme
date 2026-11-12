# AI-Coding 引擎开关配置：ai-coding-config.yml 接线派生引擎

> 源在 `.ai/ai-coding-config.yml`（仿 `.ai/env/`：源、被 `sync.mjs` 读取、**不派生**）。
> 权威用法见 `.ai/ARCHITECTURE.md`「行为/引擎开关」；本记录定「为何只门控这三个开关、为何核心派生与红线不可关」。

## 背景

用户要求创建 `ai-coding-config.yml` 对 AI-Coding 行为做动态功能启禁用，并选定**引擎强制接线**（开关真正生效，而非仅声明）。
调研：`.ai/` 已有声明式配置先例 `.ai/env/*.yaml`（AI 按需读取、不派生）；派生引擎 `sync.mjs` 中真正可选的行为只有入口形态（`--links`/`AI_SYNC_LINK`）、孤儿检测、8KB 预算；
其余派生（rules/入口/scopes/pools/tools/ci）是「全工具联动」铁律的核心保证。仓库无外部 YAML 依赖，`sync.mjs` 纯 `node:` 内置、零安装可跑。

## 决定

1. **位置与形态**：`.ai/ai-coding-config.yml`（唯一事实源根，kebab + `.yml` 合规）；是**源**，被引擎读取，**不派生**（不计入 `--check` 期望集，孤儿扫描不触及）。
2. **只门控三个安全开关**（`engine.*`）：`entryMode`(stub|symlink，CLI `--links`/`AI_SYNC_LINK=1` 优先)、`orphanDetection`(bool)、`rulesBudgetBytes`(正整数，默认 8192)。缺省即现状，完全向后兼容。
3. **无依赖解析 + 严格校验**：内置极简 YAML 子集解析（仅两层标量映射 + 注释，拒绝数组/Tab/多行/锚点）；未知键/错误类型/非法枚举在**写入前**报错（沿用引擎「先校验后写」姿态）。
4. **核心派生与红线不可关**：rules/入口/scopes/pools/tools/ci 始终派生（关掉会孤儿化派生物、破坏全工具一致）；AI 协作红线与强制规范留在 `.ai/rules/`，不在本配置开放关闭。
5. **消费路径**：引擎自动读取（钩子/CI 调用同一脚本即自动生效，**不改 CI 工作流文件**）；文档登记 ARCHITECTURE 段 + 目录树、ai-config 速查行与适用范围、`project.md` 极短指针（RULES.md 8134→8179 < 8192）。
6. **回归**：`.ai/tests/sync.test.mjs` 增 17 例（缺省兼容 / entryMode 默认与 CLI 覆盖 / 无权限降级 / 孤儿开关 / 自定义预算 / 10 类非法配置写入前拒绝 / 注释解析），全套 38 例绿、0 跳过。

## 被否的选项

- **纯声明式（AI 读取、不接线）**：否决——用户明确选引擎强制接线，声明式开关不真正生效。
- **门控 scopes/pools/tools/ci 派生类别**：否决——关闭即孤儿化既有派生物、破坏「全工具联动」铁律；安全实现需自动删除或全仓孤儿扫描，风险与范围过大（选项已 caveat「不宜随意关」）。如确需，另开任务专门设计。
- **开放 AI 行为红线开关（如 requireTests/autoSync/confirmDestructive）**：否决——这些是 `.ai/rules/` 强制规范，配置不得凌驾红线；advisory 行为开关会与铁律矛盾。
- **引入 YAML 解析依赖（js-yaml 等）**：否决——违反「不引入未登记依赖」，且 `sync.mjs` 须保持纯内置、零安装可跑；schema 固定且小，手写子集解析 + 测试足够。
- **改 CI 工作流读取配置**：否决——钩子/CI 已调用 `sync.mjs`，引擎读取即自动生效；改 CI 触发红线且无必要。
- **把配置放在仓库根或工具目录**：否决——唯一事实源是 `.ai/`，放根/工具目录会割裂事实源且可能被误当派生物。